package com.underrated.docreader.export

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.LayoutCallback
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.WriteCallback
import android.provider.DocumentsContract
import android.util.Log
import android.webkit.WebView
import androidx.core.content.FileProvider
import com.underrated.docreader.pdf.PdfEngine
import com.underrated.docreader.pdf.OpenResult
import com.underrated.docreader.pdf.PdfSource
import com.underrated.docreader.pdf.RenderTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "DocumentExport"

/** Progress for a running export, surfaced to the UI. */
sealed interface ExportState {
    data object Idle : ExportState
    data class Working(val label: String, val done: Int = 0, val total: Int = 0) : ExportState
    data class Done(val message: String) : ExportState
    data class Failed(val message: String) : ExportState
}

/**
 * Longest edge used when rasterising a page to PNG. 2000 px keeps a full A4 page
 * legible (roughly 170 dpi) while staying well inside bitmap limits.
 */
private const val IMAGE_LONG_EDGE = 2000

// ---------------------------------------------------------------- sharing

/**
 * Hands the original document to the Android Sharesheet.
 *
 * The file is copied into `cache/shared/` and served through [FileProvider]: a URI
 * this app merely *holds* a SAF grant for cannot reliably be re-granted onward, so
 * receiving apps often fail to read it. A FileProvider URI always works.
 */
suspend fun shareDocument(
    context: Context,
    source: Uri,
    displayName: String,
    mimeType: String,
): Intent = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }

    val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "document" }
    val copy = File(dir, safeName)
    context.contentResolver.openInputStream(source)?.use { input ->
        FileOutputStream(copy).use { output -> input.copyTo(output) }
    } ?: error("Could not read the document")

    val shareUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", copy)

    Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, shareUri)
        putExtra(Intent.EXTRA_TITLE, displayName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

// ---------------------------------------------------------------- PDF source

/**
 * "Export to PDF" for a document that already is a PDF: copy the bytes.
 *
 * Re-rendering would rasterise the page and throw away the text layer, so a byte
 * copy is strictly the highest-fidelity result available.
 */
/**
 * "Download": writes the original document, byte for byte, to a location the user
 * picked in the system Save As dialog. No re-rendering and no format change — a
 * .docx stays a .docx, a .xlsx stays a .xlsx.
 */
suspend fun saveOriginalTo(context: Context, source: Uri, destination: Uri): Unit =
    withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(source)?.use { input ->
            context.contentResolver.openOutputStream(destination)?.use { output ->
                input.copyTo(output)
            } ?: error("Could not open the destination file")
        } ?: error("Could not read the document")
        Unit
    }

suspend fun exportPdfCopy(context: Context, source: Uri, destination: Uri): Unit =
    withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(source)?.use { input ->
            context.contentResolver.openOutputStream(destination)?.use { output ->
                input.copyTo(output)
            } ?: error("Could not open the destination file")
        } ?: error("Could not read the document")
        Unit
    }

/**
 * Rasterises every page of [pdf] into the SAF tree [treeUri] as PNGs.
 * Returns the number of pages written.
 */
suspend fun exportPagesAsImages(
    context: Context,
    pdf: PdfSource,
    treeUri: Uri,
    baseName: String,
    onProgress: (done: Int, total: Int) -> Unit,
): Int = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val dirUri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )

    val total = pdf.pageCount
    for (index in 0 until total) {
        val size = pdf.pageSize(index)
        val scale = IMAGE_LONG_EDGE.toFloat() / maxOf(size.widthPt, size.heightPt).toFloat()
        val width = (size.widthPt * scale).toInt().coerceAtLeast(1)
        val height = (size.heightPt * scale).toInt().coerceAtLeast(1)

        val bitmap = pdf.renderPage(index, RenderTarget(width, height))
            ?: error("Page ${index + 1} could not be rendered")

        val name = "%s_page_%03d.png".format(baseName, index + 1)
        val fileUri = DocumentsContract.createDocument(resolver, dirUri, "image/png", name)
            ?: error("Could not create $name in the chosen folder")

        resolver.openOutputStream(fileUri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: error("Could not write $name")

        bitmap.recycle()
        onProgress(index + 1, total)
    }
    total
}

// ---------------------------------------------------------------- WebView (DOCX)

/**
 * Writes the WebView's content to [destination] as a real PDF, using the same
 * print pipeline the system print dialog uses. The output is vector, paginated by
 * the renderer, and keeps selectable text.
 *
 * Returns the page count the print layout produced.
 */
suspend fun exportWebViewAsPdf(
    webView: WebView,
    destination: ParcelFileDescriptor,
    documentName: String,
): Int {
    val adapter: PrintDocumentAdapter = withContext(Dispatchers.Main) {
        webView.createPrintDocumentAdapter(documentName)
    }

    val attributes = PrintAttributes.Builder()
        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
        .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
        .build()

    val info: PrintDocumentInfo = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            adapter.onStart()
            adapter.onLayout(
                null,
                attributes,
                CancellationSignal(),
                LayoutCallback(
                    finished = { if (cont.isActive) cont.resume(it) },
                    failed = {
                        if (cont.isActive) {
                            cont.resumeWithException(IllegalStateException(it?.toString() ?: "Print layout failed"))
                        }
                    },
                ),
                null,
            )
        }
    }

    withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            adapter.onWrite(
                arrayOf(PageRange.ALL_PAGES),
                destination,
                CancellationSignal(),
                WriteCallback(
                    finished = { if (cont.isActive) cont.resume(Unit) },
                    failed = {
                        if (cont.isActive) {
                            cont.resumeWithException(IllegalStateException(it?.toString() ?: "Print write failed"))
                        }
                    },
                ),
            )
        }
    }

    withContext(Dispatchers.Main) { adapter.onFinish() }
    // The WebView print adapter reports PAGE_COUNT_UNKNOWN (-1) rather than a real
    // count, so this is only ever a log line — the viewer's page counter is derived
    // from the rendered geometry instead.
    Log.i(
        TAG,
        if (info.pageCount == PrintDocumentInfo.PAGE_COUNT_UNKNOWN) {
            "webview pdf export: written, page count not reported by the adapter"
        } else {
            "webview pdf export: ${info.pageCount} page(s)"
        },
    )
    return info.pageCount
}

/**
 * Images for a DOCX are produced by exporting to PDF first and then rasterising
 * that, so screen rendering and exported pages share one pagination model instead
 * of inventing a second one.
 */
suspend fun exportWebViewAsImages(
    context: Context,
    webView: WebView,
    treeUri: Uri,
    baseName: String,
    onProgress: (done: Int, total: Int) -> Unit,
): Int {
    val temp = withContext(Dispatchers.IO) {
        File.createTempFile("export", ".pdf", context.cacheDir)
    }
    try {
        ParcelFileDescriptor.open(
            temp,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE,
        ).use { fd ->
            exportWebViewAsPdf(webView, fd, baseName)
        }

        return withContext(Dispatchers.IO) {
            when (val opened = PdfEngine.PDFIUM.open(context, Uri.fromFile(temp))) {
                is OpenResult.Success -> opened.source.use { pdf ->
                    exportPagesAsImages(context, pdf, treeUri, baseName, onProgress)
                }

                is OpenResult.Failure ->
                    error("The exported PDF could not be read back (${opened.reason})")
            }
        }
    } finally {
        withContext(Dispatchers.IO) { temp.delete() }
    }
}

private inline fun <T> PdfSource.use(block: (PdfSource) -> T): T = try {
    block(this)
} finally {
    close()
}

/** Opens a SAF destination for writing, as a file descriptor. */
fun openForWriting(resolver: ContentResolver, destination: Uri): ParcelFileDescriptor =
    resolver.openFileDescriptor(destination, "w")
        ?: error("Could not open the destination file")
