package com.underrated.docreader.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPasswordException
import io.legere.pdfiumandroid.PdfiumCore

/**
 * [PdfSource] backed by PDFium (io.legere:pdfiumandroid, a maintained Kotlin fork of
 * PdfiumAndroid over Chrome's PDF engine).
 *
 * Spike implementation, written to answer three questions the platform renderer could
 * not: what does the native payload cost, how fast does it rasterise compared to
 * PdfRenderer, and can it open password-protected files.
 *
 * Note how little had to change: PDFium's
 * `renderPageBitmap(bitmap, startX, startY, drawSizeX, drawSizeY, …)` takes the page
 * origin relative to the bitmap and the full rendered page size, which is exactly the
 * patch model [RenderTarget] already expresses. The viewer above is untouched.
 */
class PdfiumSource private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val document: PdfDocument,
) : PdfSource {

    private val lock = Any()
    private var closed = false

    override val pageCount: Int = document.getPageCount()

    private val sizes = arrayOfNulls<PageSize>(pageCount)

    override fun pageSize(index: Int): PageSize {
        sizes[index]?.let { return it }
        synchronized(lock) {
            sizes[index]?.let { return it }
            if (closed) return FALLBACK_SIZE
            return try {
                document.openPage(index).use { page ->
                    PageSize(page.getPageWidthPoint(), page.getPageHeightPoint())
                        .also { sizes[index] = it }
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
                bitmap = Bitmap.createBitmap(
                    target.outWidthPx,
                    target.outHeightPx,
                    Bitmap.Config.ARGB_8888,
                )
                bitmap.eraseColor(Color.WHITE)
                document.openPage(index).use { page ->
                    page.renderPageBitmap(
                        bitmap,
                        -target.patchLeft,
                        -target.patchTop,
                        target.fullWidthPx,
                        target.fullHeightPx,
                        true,  // render annotations
                        false, // no text mask
                    )
                }
                bitmap
            } catch (oom: OutOfMemoryError) {
                Log.w(TAG, "OOM rendering page $index")
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
            runCatching { document.close() }
            runCatching { descriptor.close() }
        }
    }

    companion object {
        private const val TAG = "PdfiumSource"
        private val FALLBACK_SIZE = PageSize(612, 792)

        fun open(context: Context, uri: Uri, password: String?): OpenResult {
            val descriptor: ParcelFileDescriptor = try {
                context.contentResolver.openFileDescriptor(uri, "r")
                    ?: return OpenResult.Failure(OpenFailure.UNREADABLE, "No file descriptor for $uri")
            } catch (t: Throwable) {
                return OpenResult.Failure(OpenFailure.UNREADABLE, t.message)
            }

            return try {
                val core = PdfiumCore(context)
                val document = core.newDocument(descriptor, password)
                OpenResult.Success(
                    PdfiumSource(descriptor, document),
                    queryDisplayName(context, uri),
                )
            } catch (pw: PdfPasswordException) {
                // Unlike the platform renderer, this is recoverable: ask the user for
                // the password and call open() again with it.
                runCatching { descriptor.close() }
                OpenResult.Failure(OpenFailure.PASSWORD_PROTECTED, pw.message)
            } catch (t: Throwable) {
                runCatching { descriptor.close() }
                OpenResult.Failure(OpenFailure.CORRUPT_OR_UNSUPPORTED, t.message)
            }
        }
    }
}
