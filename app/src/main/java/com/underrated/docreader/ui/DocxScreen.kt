package com.underrated.docreader.ui

import android.net.Uri
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.underrated.docreader.docx.createDocxWebView
import com.underrated.docreader.export.exportWebViewAsImages
import com.underrated.docreader.export.exportWebViewAsPdf
import com.underrated.docreader.export.openForWriting

const val DOCX_MIME_TYPE =
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

/**
 * What a DOCX export cannot promise. Shown before an export runs rather than
 * producing a file that quietly differs from the original.
 */
private const val DOCX_EXPORT_CAVEAT =
    "This exports what the viewer renders, not a Word re-layout.\n\n" +
        "Page breaks follow this renderer's geometry, so they may not match " +
        "Microsoft Word page for page. Vertically merged table cells, text boxes, " +
        "SmartArt, tracked changes, footnotes, comments and embedded fonts may be " +
        "missing or differ."

/**
 * Phase 2 prototype viewer for Word documents.
 *
 * Deliberately NOT the PDF viewer. A DOCX has no fixed pagination to honour, so this
 * is a continuous scrolling surface with the WebView's own pinch zoom, rather than
 * the paged canvas with fit-to-width and a page slider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocxScreen(
    uri: Uri,
    title: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current

    var rendering by remember { mutableStateOf(true) }
    var sectionCount by remember { mutableIntStateOf(0) }
    var currentPage by remember { mutableIntStateOf(1) }
    var totalPages by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(webView) {
        val view = webView
        onDispose {
            view?.stopLoading()
            view?.removeJavascriptInterface("DocReader")
            view?.webViewClient = android.webkit.WebViewClient()
            view?.destroy()
            if (webView === view) webView = null
        }
    }

    val baseName = remember(title) { title.substringBeforeLast('.').ifBlank { "document" } }

    val target = remember(uri, baseName) {
        object : ExportTarget {
            override suspend fun exportPdf(destination: Uri) {
                val view = webView ?: error("The document is still loading")
                openForWriting(context.contentResolver, destination).use { fd ->
                    exportWebViewAsPdf(view, fd, baseName)
                }
            }

            override suspend fun exportImages(
                treeUri: Uri,
                onProgress: (Int, Int) -> Unit,
            ): Int {
                val view = webView ?: error("The document is still loading")
                return exportWebViewAsImages(context, view, treeUri, baseName, onProgress)
            }

            override val exportCaveat: String get() = DOCX_EXPORT_CAVEAT
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackArrow(onClick = onClose) },
                title = {
                    Column {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = if (sectionCount > 1) {
                                "docx-preview · $sectionCount sections"
                            } else {
                                "docx-preview (prototype)"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    DocumentOverflowMenu(
                        documentUri = uri,
                        displayName = title,
                        mimeType = DOCX_MIME_TYPE,
                        target = target,
                    )
                },
            )
        },
    ) { padding: PaddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    createDocxWebView(
                        context = ctx,
                        documentUri = uri,
                        onRendered = { sections ->
                            sectionCount = sections
                            rendering = false
                        },
                        onPage = { page, total ->
                            currentPage = page
                            totalPages = total
                        },
                        onError = { message ->
                            error = message
                            rendering = false
                        },
                    ).also { webView = it }
                },
            )

            if (rendering) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Opening document", style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (!rendering && error == null) {
                PageCounterPill(
                    current = currentPage,
                    total = totalPages,
                    approximate = true,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            error?.let { message ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 4.dp,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "This document could not be displayed",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
    }
}
