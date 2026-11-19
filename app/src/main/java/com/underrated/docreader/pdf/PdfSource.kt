package com.underrated.docreader.pdf

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Page dimensions in PDF points (1/72 inch), as reported by the engine.
 */
data class PageSize(val widthPt: Int, val heightPt: Int) {
    val aspect: Float get() = if (widthPt <= 0) 1f else heightPt.toFloat() / widthPt.toFloat()
}

/**
 * What to rasterise.
 *
 * The page is rendered as if it were [fullWidthPx] x [fullHeightPx], but only the
 * rectangle starting at ([patchLeft], [patchTop]) of size [outWidthPx] x [outHeightPx]
 * is written into the returned bitmap. Asking for the whole page is simply
 * patch = (0, 0) and out = full.
 *
 * This keeps memory proportional to the viewport instead of to the zoom level: a page
 * at 8x zoom still costs one screen-sized bitmap.
 */
data class RenderTarget(
    val fullWidthPx: Int,
    val fullHeightPx: Int,
    val patchLeft: Int = 0,
    val patchTop: Int = 0,
    val outWidthPx: Int = fullWidthPx,
    val outHeightPx: Int = fullHeightPx,
)

/**
 * The rendering engine seam.
 *
 * Phase 1 ships exactly one implementation ([PdfRendererSource], backed by the
 * platform's android.graphics.pdf.PdfRenderer). Everything above this interface —
 * layout, gestures, caching, the viewer UI — is engine-agnostic, so swapping in a
 * PDFium-backed implementation later means adding one class, not rewriting the viewer.
 */
interface PdfSource {
    val pageCount: Int

    /** Cheap after the first call; implementations are expected to memoise. */
    fun pageSize(index: Int): PageSize

    /** Returns null if the page could not be rendered (closed source, OOM, bad page). */
    fun renderPage(index: Int, target: RenderTarget): Bitmap?

    fun close()
}

/** Why a document could not be opened, in terms the UI can explain to a human. */
enum class OpenFailure {
    /** The engine refused the file because it is password protected. */
    PASSWORD_PROTECTED,

    /** Not a PDF, truncated, or otherwise unparseable. */
    CORRUPT_OR_UNSUPPORTED,

    /** The URI could not be read at all (revoked permission, deleted file). */
    UNREADABLE,
}

sealed interface OpenResult {
    data class Success(val source: PdfSource, val displayName: String?) : OpenResult
    data class Failure(val reason: OpenFailure, val detail: String?) : OpenResult
}

/**
 * The available rendering backends. [PDFIUM] is the default.
 *
 * Measured on the test corpus (emulator x86_64): PDFium rasterises 2.0-3.7x faster,
 * measures pages ~17x faster, and is the only one of the two that can open encrypted
 * files. It costs ~4.9 MB of native library on arm64. [PLATFORM] is kept because it
 * ships with the OS, costs nothing, and is a useful fallback if PDFium ever
 * misrenders a document.
 */
enum class PdfEngine(val label: String, val supportsPassword: Boolean) {
    PDFIUM("PDFium", supportsPassword = true),
    PLATFORM("Android PdfRenderer", supportsPassword = false);

    companion object {
        val Default = PDFIUM
    }

    fun open(context: Context, uri: Uri, password: String? = null): OpenResult = when (this) {
        PLATFORM -> PdfRendererSource.open(context, uri)
        PDFIUM -> PdfiumSource.open(context, uri, password)
    }
}

/** The human-facing filename behind a content URI, when the provider exposes one. */
internal fun queryDisplayName(context: Context, uri: Uri): String? = try {
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor: Cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
} catch (t: Throwable) {
    null
}
