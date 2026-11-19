package com.underrated.docreader.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.IOException

/**
 * [PdfSource] backed by the platform renderer (android.graphics.pdf.PdfRenderer,
 * available since API 21).
 *
 * Two hard constraints of that API shape this class:
 *
 *  1. Only ONE page may be open at a time. Opening a second page while one is open
 *     throws. Every call therefore opens, uses and closes a page inside one lock.
 *  2. The renderer is not thread safe. All access is serialised on [lock].
 *
 * Constraint 1 is the same shape as MuPDF's single cached page, so the cost model is
 * familiar: page objects are cheap to reopen, and the expensive part — rasterising —
 * is what we cache above this layer.
 */
class PdfRendererSource private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : PdfSource {

    private val lock = Any()
    private var closed = false

    override val pageCount: Int = renderer.pageCount

    private val sizes = arrayOfNulls<PageSize>(pageCount)

    override fun pageSize(index: Int): PageSize {
        sizes[index]?.let { return it }
        synchronized(lock) {
            sizes[index]?.let { return it }
            if (closed) return FALLBACK_SIZE
            return try {
                renderer.openPage(index).use { page ->
                    PageSize(page.width, page.height).also { sizes[index] = it }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "pageSize($index) failed", t)
                FALLBACK_SIZE
            }
        }
    }

    override fun renderPage(index: Int, target: RenderTarget): Bitmap? {
        if (target.outWidthPx <= 0 || target.outHeightPx <= 0) return null
        synchronized(lock) {
            if (closed) return null
            var bitmap: Bitmap? = null
            return try {
                renderer.openPage(index).use { page ->
                    bitmap = Bitmap.createBitmap(
                        target.outWidthPx,
                        target.outHeightPx,
                        Bitmap.Config.ARGB_8888,
                    )
                    // PdfRenderer composites onto whatever is already in the bitmap and
                    // does not paint a page background, so an unpainted bitmap shows
                    // through as transparent/black. Fill it first.
                    bitmap!!.eraseColor(Color.WHITE)

                    val matrix = Matrix()
                    matrix.setScale(
                        target.fullWidthPx.toFloat() / page.width.toFloat(),
                        target.fullHeightPx.toFloat() / page.height.toFloat(),
                    )
                    if (target.patchLeft != 0 || target.patchTop != 0) {
                        matrix.postTranslate(-target.patchLeft.toFloat(), -target.patchTop.toFloat())
                    }

                    page.render(bitmap!!, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            } catch (oom: OutOfMemoryError) {
                Log.w(TAG, "OOM rendering page $index at ${target.outWidthPx}x${target.outHeightPx}")
                bitmap?.recycle()
                null
            } catch (t: Throwable) {
                Log.w(TAG, "render($index) failed", t)
                bitmap?.recycle()
                null
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
        }
    }

    companion object {
        private const val TAG = "PdfRendererSource"
        private val FALLBACK_SIZE = PageSize(612, 792) // US Letter, used only if a page errors

        fun open(context: Context, uri: Uri): OpenResult {
            val descriptor: ParcelFileDescriptor = try {
                context.contentResolver.openFileDescriptor(uri, "r")
                    ?: return OpenResult.Failure(OpenFailure.UNREADABLE, "No file descriptor for $uri")
            } catch (t: Throwable) {
                return OpenResult.Failure(OpenFailure.UNREADABLE, t.message)
            }

            return try {
                val renderer = PdfRenderer(descriptor)
                OpenResult.Success(PdfRendererSource(descriptor, renderer), queryDisplayName(context, uri))
            } catch (se: SecurityException) {
                // Documented behaviour: PdfRenderer throws SecurityException for a
                // password protected file. The platform API offers no way to supply a
                // password, so this is a genuine engine limitation, not a bug.
                runCatching { descriptor.close() }
                OpenResult.Failure(OpenFailure.PASSWORD_PROTECTED, se.message)
            } catch (io: IOException) {
                runCatching { descriptor.close() }
                OpenResult.Failure(OpenFailure.CORRUPT_OR_UNSUPPORTED, io.message)
            } catch (t: Throwable) {
                runCatching { descriptor.close() }
                OpenResult.Failure(OpenFailure.CORRUPT_OR_UNSUPPORTED, t.message)
            }
        }
    }
}
