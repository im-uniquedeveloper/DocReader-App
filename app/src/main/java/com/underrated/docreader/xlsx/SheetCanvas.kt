package com.underrated.docreader.xlsx

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/** Scroll/zoom state for one sheet. Recreated when the user switches tabs. */
class SheetViewState(
    val sheet: Sheet,
    private val styles: (Int) -> CellStyle,
    private val scope: CoroutineScope,
    private val density: Float,
) {
    var scale by mutableFloatStateOf(1f)
        private set
    var scrollX by mutableFloatStateOf(0f)
        private set
    var scrollY by mutableFloatStateOf(0f)
        private set
    var viewport by mutableStateOf(IntSize.Zero)
        private set

    private var flingJob: Job? = null

    val rowHeaderWidth: Float get() = 52f * density
    val columnHeaderHeight: Float get() = 30f * density

    var layout = SheetLayout(sheet, density * scale)
        private set

    val painter get() = SheetPainter(sheet, styles, layout)

    val frozenWidth: Float
        get() = if (sheet.frozen.columns > 0) layout.columnX(sheet.frozen.columns) else 0f
    val frozenHeight: Float
        get() = if (sheet.frozen.rows > 0) layout.rowY(sheet.frozen.rows) else 0f

    private val bodyWidth: Float
        get() = max(0f, viewport.width - rowHeaderWidth - frozenWidth)
    private val bodyHeight: Float
        get() = max(0f, viewport.height - columnHeaderHeight - frozenHeight)

    val maxScrollX: Float get() = max(0f, layout.totalWidth - frozenWidth - bodyWidth)
    val maxScrollY: Float get() = max(0f, layout.totalHeight - frozenHeight - bodyHeight)

    fun onViewportChanged(size: IntSize) {
        viewport = size
        clamp()
    }

    fun onGestureStart() {
        flingJob?.cancel()
    }

    fun transform(centroid: Offset, pan: Offset, zoomChange: Float) {
        if (zoomChange != 1f) {
            val previous = scale
            scale = (scale * zoomChange).coerceIn(0.4f, 4f)
            if (scale != previous) {
                val k = scale / previous
                layout = SheetLayout(sheet, density * scale)
                // Keep the sheet point under the fingers where it is.
                scrollX = (scrollX + centroid.x) * k - centroid.x
                scrollY = (scrollY + centroid.y) * k - centroid.y
            }
        }
        scrollX -= pan.x
        scrollY -= pan.y
        clamp()
    }

    fun onGestureEnd(velocityX: Float, velocityY: Float) {
        flingJob?.cancel()
        val decay = splineBasedDecay<Float>(androidx.compose.ui.unit.Density(density))
        flingJob = scope.launch {
            val jobs = mutableListOf<Job>()
            if (abs(velocityY) > 80f && maxScrollY > 0f) {
                jobs += launch {
                    AnimationState(initialValue = scrollY, initialVelocity = -velocityY)
                        .animateDecay(decay) {
                            val clamped = value.coerceIn(0f, maxScrollY)
                            scrollY = clamped
                            if (clamped != value) cancelAnimation()
                        }
                }
            }
            if (abs(velocityX) > 80f && maxScrollX > 0f) {
                jobs += launch {
                    AnimationState(initialValue = scrollX, initialVelocity = -velocityX)
                        .animateDecay(decay) {
                            val clamped = value.coerceIn(0f, maxScrollX)
                            scrollX = clamped
                            if (clamped != value) cancelAnimation()
                        }
                }
            }
            jobs.forEach { it.join() }
        }
    }

    /** Top-left visible cell, for the status line. */
    fun firstVisibleCell(): Pair<Int, Int> {
        val col = layout.columnAt(frozenWidth + scrollX)
        val row = layout.rowAt(frozenHeight + scrollY)
        return row to col
    }

    private fun clamp() {
        scrollX = scrollX.coerceIn(0f, maxScrollX)
        scrollY = scrollY.coerceIn(0f, maxScrollY)
    }
}

/**
 * The spreadsheet surface: frozen panes, row/column headers and the scrollable body
 * are four regions painted by one [SheetPainter], so the body and the frozen strips
 * can never disagree about geometry.
 */
@Composable
fun SheetCanvas(state: SheetViewState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { state.onViewportChanged(it) }
            .pointerInput(state) {
                awaitEachGesture {
                    val tracker = VelocityTracker()
                    val down = awaitFirstDown(requireUnconsumed = false)
                    state.onGestureStart()
                    var event: PointerEvent
                    do {
                        event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        val centroid = event.calculateCentroid(useCurrent = true)
                        if (zoom != 1f || pan != Offset.Zero) {
                            state.transform(centroid, pan, zoom)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                        if (pressed == 1) {
                            event.changes.firstOrNull { it.pressed }?.let {
                                tracker.addPosition(it.uptimeMillis, it.position)
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    val velocity = tracker.calculateVelocity()
                    state.onGestureEnd(velocity.x, velocity.y)
                }
            },
    ) {
        val painter = state.painter
        val layout = state.layout
        val headerW = state.rowHeaderWidth
        val headerH = state.columnHeaderHeight
        val frozenW = state.frozenWidth
        val frozenH = state.frozenHeight
        val w = size.width
        val h = size.height

        drawIntoCanvas { compose ->
            val canvas = compose.nativeCanvas
            painter.background(canvas, w, h)

            val bodyLeft = headerW + frozenW
            val bodyTop = headerH + frozenH
            val bodyW = max(0f, w - bodyLeft)
            val bodyH = max(0f, h - bodyTop)

            // body (scrolls both ways)
            painter.paintCells(
                canvas,
                originX = frozenW + state.scrollX,
                originY = frozenH + state.scrollY,
                left = bodyLeft, top = bodyTop, width = bodyW, height = bodyH,
                scale = density * state.scale,
            )

            // frozen rows: scrolls horizontally only
            if (frozenH > 0f) {
                painter.paintCells(
                    canvas,
                    originX = frozenW + state.scrollX, originY = 0f,
                    left = bodyLeft, top = headerH, width = bodyW, height = frozenH,
                    scale = density * state.scale,
                )
            }
            // frozen columns: scrolls vertically only
            if (frozenW > 0f) {
                painter.paintCells(
                    canvas,
                    originX = 0f, originY = frozenH + state.scrollY,
                    left = headerW, top = bodyTop, width = frozenW, height = bodyH,
                    scale = density * state.scale,
                )
            }
            // frozen corner: fixed
            if (frozenW > 0f && frozenH > 0f) {
                painter.paintCells(
                    canvas,
                    originX = 0f, originY = 0f,
                    left = headerW, top = headerH, width = frozenW, height = frozenH,
                    scale = density * state.scale,
                )
            }

            // headers
            if (frozenW > 0f) {
                painter.paintColumnHeaders(canvas, 0f, headerW, 0f, frozenW, headerH, density)
            }
            painter.paintColumnHeaders(
                canvas, frozenW + state.scrollX, bodyLeft, 0f, bodyW, headerH, density,
            )
            if (frozenH > 0f) {
                painter.paintRowHeaders(canvas, 0f, 0f, headerH, headerW, frozenH, density)
            }
            painter.paintRowHeaders(
                canvas, frozenH + state.scrollY, 0f, bodyTop, headerW, bodyH, density,
            )
            painter.paintCorner(canvas, 0f, 0f, headerW, headerH)
        }
    }
}
