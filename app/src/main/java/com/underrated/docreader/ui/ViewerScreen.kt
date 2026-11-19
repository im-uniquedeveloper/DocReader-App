package com.underrated.docreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.underrated.docreader.export.exportPagesAsImages
import com.underrated.docreader.export.exportPdfCopy
import com.underrated.docreader.viewer.PdfCanvas
import com.underrated.docreader.viewer.PdfViewerState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    state: PdfViewerState,
    uri: Uri,
    title: String,
    engineLabel: String,
    onClose: () -> Unit,
) {
    val page = state.currentPage
    val context = LocalContext.current
    val baseName = remember(title) { title.substringBeforeLast('.').ifBlank { "document" } }

    val target = remember(state, uri) {
        object : ExportTarget {
            // The source already is a PDF: copying the bytes keeps the text layer and
            // every vector, which re-rendering to a new PDF would destroy.
            override suspend fun exportPdf(destination: Uri) =
                exportPdfCopy(context, uri, destination)

            override suspend fun exportImages(
                treeUri: Uri,
                onProgress: (Int, Int) -> Unit,
            ): Int = exportPagesAsImages(context, state.source, treeUri, baseName, onProgress)

            override val exportCaveat: String? get() = null
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
                            text = engineLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    DocumentOverflowMenu(
                        documentUri = uri,
                        displayName = title,
                        mimeType = "application/pdf",
                        target = target,
                        // The document already is a PDF; Download covers saving it.
                        showExportPdf = false,
                    )
                },
            )
        },
        // No bottomBar: the page slider, its Previous/Next buttons and the surrounding
        // Surface were one component and have been removed together, so the document
        // runs to the bottom edge with no leftover container or padding. Zooming is
        // unchanged â€” pinch zoom is handled by PdfCanvas; double-tap zoom is disabled.
    ) { padding: PaddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            PdfCanvas(state = state, modifier = Modifier.fillMaxSize())

            if (state.ready) {
                // Exact: these are real PDF pages, tracked as the user scrolls and
                // recomputed after zoom and rotation.
                PageCounterPill(
                    current = page + 1,
                    total = state.pageCount,
                    approximate = false,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            if (!state.ready) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = if (state.pageCount > 0) {
                            "Measuring pages ${state.preparedPages} / ${state.pageCount}"
                        } else {
                            "Opening document"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.pageCount > 0) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = {
                                state.preparedPages.toFloat() / state.pageCount.toFloat()
                            },
                            modifier = Modifier.width(200.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Shown over the whole screen while a file is being opened. */
@Composable
fun FullScreenLoader(label: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(16.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
