package com.underrated.docreader.viewer

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.splineBasedDecay
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import com.underrated.docreader.pdf.PageBitmapCache
import com.underrated.docreader.pdf.PdfSource
import com.underrated.docreader.pdf.RenderTarget
import com.underrated.docreader.pdf.RenderedPage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Everything the viewer needs to know: layout, scroll/zoom state, and which bitmaps
 * exist right now.
 *
 * Layout model
 * ------------
 * Zoom 1.0 means "fit to viewport width". Content width is therefore
 * `viewportWidth * zoom`, and every other dimension (page heights, the gap between
 * pages, the outer padding) is expressed as a multiple of content width. That makes
 * the whole document linear in zoom, which in turn makes focal-point zooming exact:
 *
 *     offsetNew = (offsetOld + focal) * (zoomNew / zoomOld) - focal
 *
 * Rendering model
 * ---------------
 * Two tiers per page, the pattern taken from MuPDF's reader:
 *
 *   preview  a whole page rasterised once at a modest width, kept in an LRU cache and
 *            stretched to whatever size the page currently occupies. Always something
 *            to draw, never blank.
 *   patch    the visible slice of a page rasterised at the *current* zoom, replacing
 *            the stretched preview with sharp pixels. Costs one viewport-sized bitmap
 *            regardless of zoom level.
 *
 * Patches are only produced when the gesture settles (see the settle loop in [init]),
 * because rasterising per frame is not affordable. While the user is moving, the last
 * patch keeps being drawn, rescaled to the new zoom.
 */
class PdfViewerState(
    /** Exposed so exports can rasterise pages through the same engine. */
    val source: PdfSource,
    scope: CoroutineScope,
    private val renderDispatcher: CoroutineDispatcher,
) {
    private val stateScope = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))
    val pageCount: Int = source.pageCount

    /** height / width per page, filled by [prepare]. */
    private val aspect = FloatArray(pageCount) { DEFAULT_ASPECT }

    /** Which entries of [aspect] are real measurements rather than estimates. */
    private val measured = BooleanArray(pageCount)

    /** Cumulative page tops, in units of content width. */
    private val unitTop = FloatArray(pageCount)
    private var unitContentHeight = 0f

    var ready by mutableStateOf(false)
        private set
    var preparedPages by mutableIntStateOf(0)
        private set

    var viewportSize by mutableStateOf(IntSize.Zero)
        private set
    private var viewportDensity = 1f
    private var initialPagesMeasured = false
    private var initialFitApplied = false

    var zoom by mutableFloatStateOf(MIN_ZOOM)
        private set
    var offsetX by mutableFloatStateOf(0f)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set

    var interacting by mutableStateOf(false)
        private set

    /** Set by the canvas; needed for platform-accurate fling physics. */
    var density: Density? = null

    private val previews = PageBitmapCache()

    /** Sharp patches for currently visible pages, keyed by page index. */
    val patches = mutableStateMapOf<Int, RenderedPage>()

    /** Previews promoted into state so the canvas recomposes when one arrives. */
    val previewVersion = mutableIntStateOf(0)

    private val previewJobs = HashMap<Int, Job>()
    private var patchJob: Job? = null
    private var flingJob: Job? = null

    init {
        // Settle loop: whenever zoom/scroll/interaction changes, wait for things to go
        // quiet, then render sharp patches for what is on screen. collectLatest cancels
        // the pending delay on every new change, so this fires once per gesture, not
        // once per frame.
        stateScope.launch {
            snapshotFlow { ViewSnapshot(zoom, offsetX, offsetY, interacting, viewportSize) }
                .collectLatest { snapshot ->
                    if (!snapshot.ready()) return@collectLatest
                    delay(SETTLE_DELAY_MS)
                    if (!interacting) refreshPatches()
                }
        }
    }

    private data class ViewSnapshot(
        val zoom: Float,
        val offsetX: Float,
        val offsetY: Float,
        val interacting: Boolean,
        val viewport: IntSize,
    ) {
        fun ready() = viewport.width > 0 && viewport.height > 0
    }

    // ---------------------------------------------------------------- preparation

    /**
     * Measures a first window of pages, shows the document, then keeps measuring the
     * rest in the background.
     *
     * Measuring every page before the first frame gives perfect scroll extents but
     * costs time proportional to page count (1.5 s for 400 pages on the test
     * emulator), during which the user stares at a spinner. Instead the first
     * [INITIAL_MEASURE] pages are measured synchronously â€” enough to lay out the first
     * screens exactly â€” and the remainder are estimated from the last measured page
     * until their real size arrives. Each batch re-runs the layout while holding the
     * page under the viewport in place, so the extent tightens up without the content
     * jumping under the reader.
     */
    suspend fun prepare() {
        val started = android.os.SystemClock.elapsedRealtime()
        val initial = min(pageCount, INITIAL_MEASURE)

        withContext(renderDispatcher) {
            for (i in 0 until initial) measureInto(i)
        }
        rebuildLayout(anchor = null)
        preparedPages = initial
        initialPagesMeasured = true
        applyInitialFitWhenReady()
        android.util.Log.i(
            PERF_TAG,
            "measured first $initial of $pageCount pages in " +
                "${android.os.SystemClock.elapsedRealtime() - started} ms",
        )

        if (initial < pageCount) {
            stateScope.launch { measureRemaining(initial, started) }
        }
    }

    private fun measureInto(index: Int) {
        aspect[index] = source.pageSize(index).aspect.coerceIn(0.05f, 20f)
        measured[index] = true
    }

    private suspend fun measureRemaining(from: Int, started: Long) {
        var next = from
        while (next < pageCount) {
            val end = min(pageCount, next + MEASURE_BATCH)
            withContext(renderDispatcher) {
                for (i in next until end) measureInto(i)
            }
            rebuildLayout(anchor = captureAnchor())
            preparedPages = end
            next = end
        }
        android.util.Log.i(
            PERF_TAG,
            "measured all $pageCount pages in " +
                "${android.os.SystemClock.elapsedRealtime() - started} ms",
        )
    }

    /**
     * Where the top of the viewport currently sits, as (page, fraction into that page).
     * Expressed in unit space so it survives a change of zoom, viewport or layout.
     */
    private fun captureAnchor(): Anchor? {
        if (!ready || contentWidth <= 0f) return null
        val unitViewTop = (offsetY - verticalCentering) / contentWidth
        var page = 0
        for (i in 0 until pageCount) {
            if (unitTop[i] <= unitViewTop) page = i else break
        }
        return Anchor(page, unitViewTop - unitTop[page])
    }

    private data class Anchor(val page: Int, val withinPageUnit: Float)

    /** Recomputes page offsets from the current aspect ratios, optionally re-anchoring. */
    private fun rebuildLayout(anchor: Anchor?) {
        // Pages not yet measured inherit the last known aspect, which is right far more
        // often than not: documents are overwhelmingly uniform in page size.
        var lastKnown = DEFAULT_ASPECT
        var acc = PAD_UNIT
        for (i in 0 until pageCount) {
            if (measured[i]) lastKnown = aspect[i] else aspect[i] = lastKnown
            unitTop[i] = acc
            acc += aspect[i] + GAP_UNIT
        }
        unitContentHeight = acc - GAP_UNIT + PAD_UNIT

        if (anchor != null && contentWidth > 0f) {
            val target = (unitTop[anchor.page] + anchor.withinPageUnit) * contentWidth
            offsetY = (target + verticalCentering).coerceIn(0f, maxOffsetY)
        }
    }

    // -------------------------------------------------------------------- layout

    private val horizontalInsetPx: Float get() = FIT_MARGIN_DP * viewportDensity
    private val fitWidthPx: Float
        get() = (viewportSize.width - 2f * horizontalInsetPx).coerceAtLeast(1f)
    val contentWidth: Float get() = fitWidthPx * zoom
    val pageOriginX: Float get() = horizontalInsetPx - offsetX
    val contentHeight: Float get() = contentWidth * unitContentHeight

    fun pageTop(index: Int): Float = unitTop[index] * contentWidth
    fun pageHeight(index: Int): Float = aspect[index] * contentWidth

    val maxOffsetX: Float get() = max(0f, contentWidth - fitWidthPx)
    val maxOffsetY: Float get() = max(0f, contentHeight - viewportSize.height)

    /** Extra top inset when the whole document is shorter than the viewport. */
    val verticalCentering: Float
        get() = max(0f, (viewportSize.height - contentHeight) / 2f)

    /** Index of the page under the middle of the screen, 0-based. */
    val currentPage: Int
        get() {
            if (!ready || viewportSize.height == 0) return 0
            val midY = offsetY + viewportSize.height / 2f - verticalCentering
            var lo = 0
            var hi = pageCount - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (pageTop(mid) <= midY) lo = mid else hi = mid - 1
            }
            return lo
        }

    fun visibleRange(): IntRange {
        if (!ready || viewportSize.height == 0) return IntRange.EMPTY
        val top = offsetY - verticalCentering
        val bottom = top + viewportSize.height
        var first = pageCount
        var last = -1
        for (i in 0 until pageCount) {
            val pTop = pageTop(i)
            val pBottom = pTop + pageHeight(i)
            if (pBottom >= top && pTop <= bottom) {
                if (i < first) first = i
                if (i > last) last = i
            } else if (pTop > bottom) {
                break
            }
        }
        return if (last < first) IntRange.EMPTY else first..last
    }

    // ------------------------------------------------------------------ gestures

    fun onViewportChanged(size: IntSize, density: Float) {
        val changed = size != viewportSize || density != viewportDensity
        if (!changed) return
        // Anchor on the page under the top edge rather than on a fraction of the whole
        // document: after a rotation the reader lands on the same page at the same
        // point within it, instead of somewhere proportionally similar.
        val anchor = captureAnchor()
        viewportSize = size
        viewportDensity = density.coerceAtLeast(0.1f)
        applyInitialFitWhenReady()
        if (anchor != null && contentWidth > 0f) {
            val target = (unitTop[anchor.page] + anchor.withinPageUnit) * contentWidth
            offsetY = (target + verticalCentering).coerceIn(0f, maxOffsetY)
        } else {
            offsetY = offsetY.coerceIn(0f, maxOffsetY)
        }
        offsetX = offsetX.coerceIn(0f, maxOffsetX)
        patches.clear()
        if (ready) stateScope.launch { refreshPatches() }
    }

    fun onGestureStart() {
        flingJob?.cancel()
        interacting = true
    }

    /**
     * One step of a pan/pinch. [centroid] is in viewport coordinates.
     */
    fun transform(centroid: Offset, pan: Offset, zoomChange: Float) {
        if (!ready || viewportSize.width == 0) return
        val oldZoom = zoom
        val newZoom = (oldZoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val k = newZoom / oldZoom

        if (k != 1f) {
            // Keep the point under the fingers fixed.
            val focalX = centroid.x - horizontalInsetPx
            offsetX = (offsetX + focalX) * k - focalX
            offsetY = (offsetY + centroid.y) * k - centroid.y
            zoom = newZoom
        }
        offsetX -= pan.x
        offsetY -= pan.y
        clamp()
    }

    fun onGestureEnd(velocity: Velocity) {
        android.util.Log.i(
            PERF_TAG,
            "gestureEnd v=(${velocity.x.toInt()},${velocity.y.toInt()}) " +
                "zoom=$zoom offset=(${offsetX.toInt()},${offsetY.toInt()}) " +
                "max=(${maxOffsetX.toInt()},${maxOffsetY.toInt()})",
        )
        interacting = false
        flingJob?.cancel()
        flingJob = stateScope.launch {
            // Use the platform's own scroll decay curve rather than an invented
            // friction constant, so a fling here travels the same distance it would in
            // any other Android scrollable at the same velocity.
            val decay = density?.let { splineBasedDecay<Float>(it) }
                ?: exponentialDecay<Float>(frictionMultiplier = 1.1f)
            val vy = -velocity.y
            val vx = -velocity.x
            val jobs = mutableListOf<Job>()
            if (kotlin.math.abs(vy) > MIN_FLING_VELOCITY && maxOffsetY > 0f) {
                jobs += launch {
                    AnimationState(initialValue = offsetY, initialVelocity = vy)
                        .animateDecay(decay) {
                            val clamped = value.coerceIn(0f, maxOffsetY)
                            offsetY = clamped
                            if (clamped != value) cancelAnimation()
                        }
                }
            }
            if (kotlin.math.abs(vx) > MIN_FLING_VELOCITY && maxOffsetX > 0f) {
                jobs += launch {
                    AnimationState(initialValue = offsetX, initialVelocity = vx)
                        .animateDecay(decay) {
                            val clamped = value.coerceIn(0f, maxOffsetX)
                            offsetX = clamped
                            if (clamped != value) cancelAnimation()
                        }
                }
            }
            jobs.forEach { it.join() }
        }
    }

    /**
     * Snaps to fit-to-width with no animation. Zoom 1.0 *is* fit-to-width in this
     * layout model (content width = viewport width x zoom), so the document always
     * opens with the page spanning the screen and the text at readable size.
     */
    fun resetToFitWidth() {
        flingJob?.cancel()
        zoom = MIN_ZOOM
        offsetX = 0f
        offsetY = 0f
        patches.clear()
        initialFitApplied = true
        ready = initialPagesMeasured && viewportSize.width > 0 && viewportSize.height > 0
    }

    private fun applyInitialFitWhenReady() {
        if (!initialPagesMeasured || viewportSize.width <= 0 || viewportSize.height <= 0) return
        if (!initialFitApplied) resetToFitWidth()
        ready = true
    }

    fun fitWidth() {
        val centre = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
        animateZoomTo(MIN_ZOOM, centre)
    }

    fun zoomBy(factor: Float) {
        val centre = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
        animateZoomTo((zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM), centre)
    }

    private fun animateZoomTo(target: Float, focal: Offset) {
        flingJob?.cancel()
        flingJob = stateScope.launch {
            interacting = true
            val start = zoom
            AnimationState(initialValue = start).animateTo(
                targetValue = target,
                animationSpec = tween(durationMillis = ZOOM_ANIM_MS),
            ) {
                val k = value / zoom
                val focalX = focal.x - horizontalInsetPx
                offsetX = (offsetX + focalX) * k - focalX
                offsetY = (offsetY + focal.y) * k - focal.y
                zoom = value
                clamp()
            }
            interacting = false
        }
    }

    fun scrollToPage(index: Int) {
        if (!ready) return
        val target = index.coerceIn(0, pageCount - 1)
        flingJob?.cancel()
        offsetY = (pageTop(target) - PAD_UNIT * contentWidth).coerceIn(0f, maxOffsetY)
    }

    fun animateToPage(index: Int) {
        if (!ready) return
        val target = index.coerceIn(0, pageCount - 1)
        flingJob?.cancel()
        flingJob = stateScope.launch {
            val destination = (pageTop(target) - PAD_UNIT * contentWidth).coerceIn(0f, maxOffsetY)
            AnimationState(initialValue = offsetY).animateTo(
                targetValue = destination,
                animationSpec = tween(durationMillis = PAGE_ANIM_MS),
            ) {
                offsetY = value
            }
        }
    }

    private fun clamp() {
        offsetX = offsetX.coerceIn(0f, maxOffsetX)
        offsetY = offsetY.coerceIn(0f, maxOffsetY)
    }

    // ----------------------------------------------------------------- rendering

    private fun previewWidthFor(): Int =
        min(max(viewportSize.width, 320), PREVIEW_MAX_WIDTH)

    fun previewFor(index: Int): RenderedPage? = previews[PageBitmapCache.key(index, previewWidthFor())]

    /**
     * Kick off previews for everything on screen plus a small margin, and cancel work
     * for pages that have scrolled away. Called every frame-ish from the canvas; it is
     * cheap because it only starts jobs that do not already exist.
     */
    fun ensureVisibleContent() {
        if (!ready || viewportSize.width == 0) return
        val visible = visibleRange()
        if (visible.isEmpty()) return

        val from = max(0, visible.first - PREFETCH_PAGES)
        val to = min(pageCount - 1, visible.last + PREFETCH_PAGES)

        // Cancel previews that are no longer wanted.
        val stale = previewJobs.keys.filter { it < from || it > to }
        stale.forEach { previewJobs.remove(it)?.cancel() }

        val width = previewWidthFor()
        // Visible pages first, prefetch afterwards. Renders queue onto one thread in
        // launch order, so without this the page above the fold could be rasterised
        // before the page the reader is actually looking at.
        val order = ArrayList<Int>(visible.count() + 2 * PREFETCH_PAGES)
        for (i in visible) order.add(i)
        for (d in 1..PREFETCH_PAGES) {
            order.add(visible.last + d)
            order.add(visible.first - d)
        }

        for (i in order) {
            if (i < from || i > to) continue
            val key = PageBitmapCache.key(i, width)
            if (previews[key] != null || previewJobs.containsKey(i)) continue
            previewJobs[i] = stateScope.launch {
                val height = (width * aspect[i]).roundToInt().coerceAtLeast(1)
                val startedAt = android.os.SystemClock.elapsedRealtime()
                val bitmap = withContext(renderDispatcher) {
                    source.renderPage(i, RenderTarget(fullWidthPx = width, fullHeightPx = height))
                }
                android.util.Log.i(
                    PERF_TAG,
                    "preview page $i at ${width}px in " +
                        "${android.os.SystemClock.elapsedRealtime() - startedAt} ms",
                )
                if (bitmap != null) {
                    previews.put(key, RenderedPage(bitmap, width))
                    previewVersion.intValue++
                }
                previewJobs.remove(i)
            }
        }

        // Drop patches for pages that left the screen so they do not pin memory.
        val gone = patches.keys.filter { it < visible.first - 1 || it > visible.last + 1 }
        gone.forEach { patches.remove(it) }
    }

    /**
     * The settle step: render the visible slice of each visible page at the current
     * zoom. Skipped entirely when the preview is already at least as detailed as the
     * screen needs, which is the common case at fit-width.
     */
    private suspend fun refreshPatches() {
        if (!ready || viewportSize.width == 0) return
        val visible = visibleRange()
        if (visible.isEmpty()) return

        val fullWidth = contentWidth.roundToInt()
        if (fullWidth <= previewWidthFor() * PATCH_THRESHOLD) {
            // Preview resolution is good enough; nothing sharper to gain.
            patches.clear()
            return
        }

        patchJob?.cancel()
        patchJob = stateScope.launch {
            for (i in visible) {
                val existing = patches[i]
                if (existing != null && existing.contentWidthPx == fullWidth) continue

                // Screen-space top of this page is pageTop + verticalCentering - offsetY,
                // so the page-local coordinate of the viewport top is offsetY minus both.
                val pTop = pageTop(i) + verticalCentering
                val pHeight = pageHeight(i)

                // Intersection of this page with the viewport, in page-local pixels.
                val localLeft = max(0f, offsetX)
                val localTop = max(0f, offsetY - pTop)
                val localRight = min(contentWidth, offsetX + viewportSize.width)
                val localBottom = min(pHeight, offsetY + viewportSize.height - pTop)

                val outW = (localRight - localLeft).roundToInt()
                val outH = (localBottom - localTop).roundToInt()
                if (outW <= 0 || outH <= 0) continue

                val target = RenderTarget(
                    fullWidthPx = fullWidth,
                    fullHeightPx = pHeight.roundToInt().coerceAtLeast(1),
                    patchLeft = localLeft.roundToInt(),
                    patchTop = localTop.roundToInt(),
                    outWidthPx = outW,
                    outHeightPx = outH,
                )
                val startedAt = android.os.SystemClock.elapsedRealtime()
                val bitmap = withContext(renderDispatcher) { source.renderPage(i, target) }
                android.util.Log.i(
                    PERF_TAG,
                    "patch page $i ${outW}x$outH at content width $fullWidth in " +
                        "${android.os.SystemClock.elapsedRealtime() - startedAt} ms",
                )
                if (bitmap != null) {
                    patches[i] = RenderedPage(
                        bitmap = bitmap,
                        contentWidthPx = fullWidth,
                        patchLeft = target.patchLeft,
                        patchTop = target.patchTop,
                    )
                }
            }
        }
    }

    fun onTrimMemory() {
        previews.trim()
        patches.clear()
    }

    fun dispose() {
        stateScope.cancel()
        previewJobs.values.forEach { it.cancel() }
        previewJobs.clear()
        patchJob?.cancel()
        flingJob?.cancel()
        patches.clear()
        previews.clear()
        source.close()
    }

    companion object {
        /** Timing instrumentation; filter logcat on this tag. */
        private const val PERF_TAG = "DocReaderPerf"

        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 8f
        private const val DEFAULT_ASPECT = 1.4142f // A4 portrait, until measured

        /** Gap between pages and outer padding, as a fraction of content width. */
        private const val GAP_UNIT = 0.025f
        private const val PAD_UNIT = 0.02f

        private const val PREVIEW_MAX_WIDTH = 1280
        private const val PREFETCH_PAGES = 1
        private const val PATCH_THRESHOLD = 1.15f

        /** Pages measured before the document is shown; the rest stream in after. */
        private const val INITIAL_MEASURE = 8
        private const val MEASURE_BATCH = 48

        private const val SETTLE_DELAY_MS = 90L
        private const val ZOOM_ANIM_MS = 220
        private const val PAGE_ANIM_MS = 320
        private const val MIN_FLING_VELOCITY = 80f
        private const val FIT_MARGIN_DP = 6f
    }
}
