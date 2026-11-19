package com.underrated.docreader.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.runtime.snapshotFlow
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The document surface: one Canvas that draws every visible page, plus a single
 * gesture loop handling pan and pinch.
 *
 * Drawing straight to a Canvas rather than composing a list of page widgets keeps
 * layout out of the hot path — a pan is a redraw, not a recomposition — and gives
 * exact control over where a stale patch lands while a fresh one renders.
 */
@Composable
fun PdfCanvas(
    state: PdfViewerState,
    modifier: Modifier = Modifier,
) {
    // Keep previews flowing for whatever is on screen. snapshotFlow collapses bursts,
    // and ensureVisibleContent only starts jobs that do not already exist.
    LaunchedEffect(state) {
        snapshotFlow { listOf(state.offsetY, state.zoom, state.viewportSize, state.ready) }
            .collect { state.ensureVisibleContent() }
    }

    // Fling physics need the display density to match the platform's scroll curve.
    val density = LocalDensity.current
    LaunchedEffect(state, density) { state.density = density }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { state.onViewportChanged(it, density.density) }
            .pointerInput(state) {
                awaitEachGesture {
                    val tracker = VelocityTracker()
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downUptime = down.uptimeMillis
                    var travel = 0f
                    var wasMultiTouch = false

                    state.onGestureStart()

                    var event: PointerEvent
                    do {
                        event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount > 1) wasMultiTouch = true

                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        val centroid = event.calculateCentroid(useCurrent = true)

                        event.changes.firstOrNull { it.id == down.id }?.let {
                            travel = max(travel, (it.position - down.position).getDistance())
                        }

                        if (zoomChange != 1f || panChange != Offset.Zero) {
                            state.transform(centroid, panChange, zoomChange)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }

                        if (pressedCount == 1) {
                            event.changes.firstOrNull { it.pressed }?.let {
                                tracker.addPosition(it.uptimeMillis, it.position)
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    val upUptime = event.changes.maxOfOrNull { it.uptimeMillis } ?: downUptime
                    val wasTap = !wasMultiTouch &&
                        travel < viewConfiguration.touchSlop &&
                        (upUptime - downUptime) < viewConfiguration.longPressTimeoutMillis

                    // Double-tap zoom removed by request: a tap is just a tap, and the
                    // only zoom gesture is pinch. The document opens fit-to-width.
                    if (wasTap) {
                        state.onGestureEnd(Velocity.Zero)
                    } else {
                        state.onGestureEnd(tracker.calculateVelocity())
                    }
                }
            },
    ) {
        if (!state.ready || state.viewportSize.width == 0) return@Canvas

        // Reading these inside the draw scope subscribes the draw pass to them, so a
        // scroll or a freshly rendered page repaints without recomposing anything.
        val version = state.previewVersion.intValue
        @Suppress("UNUSED_EXPRESSION") version

        val contentWidth = state.contentWidth
        val originX = state.pageOriginX
        val originY = -state.offsetY + state.verticalCentering

        for (index in state.visibleRange()) {
            val x = originX
            val y = originY + state.pageTop(index)
            val w = contentWidth
            val h = state.pageHeight(index)

            // Page background. Also the placeholder while the first preview renders.
            drawRect(
                color = Color.White,
                topLeft = Offset(x, y),
                size = Size(w, h),
            )

            state.previewFor(index)?.let { preview ->
                drawImage(
                    image = preview.image,
                    dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
                    dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.Medium,
                )
            }

            // Sharp patch on top, rescaled if the zoom moved since it was rendered.
            state.patches[index]?.let { patch ->
                val scale = if (patch.contentWidthPx > 0) contentWidth / patch.contentWidthPx else 1f
                drawImage(
                    image = patch.image,
                    dstOffset = IntOffset(
                        (x + patch.patchLeft * scale).roundToInt(),
                        (y + patch.patchTop * scale).roundToInt(),
                    ),
                    dstSize = IntSize(
                        (patch.widthPx * scale).roundToInt().coerceAtLeast(1),
                        (patch.heightPx * scale).roundToInt().coerceAtLeast(1),
                    ),
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
    }
}
