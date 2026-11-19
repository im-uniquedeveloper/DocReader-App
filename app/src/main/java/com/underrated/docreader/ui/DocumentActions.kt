package com.underrated.docreader.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.underrated.docreader.R
import com.underrated.docreader.export.ExportState
import com.underrated.docreader.export.saveOriginalTo
import com.underrated.docreader.export.shareDocument
import kotlinx.coroutines.launch

/**
 * What a particular viewer can export. The PDF viewer fulfils this from its
 * PdfSource; the DOCX viewer fulfils it from the WebView's print pipeline.
 */
interface ExportTarget {
    /** Write the document to [destination] as a PDF. */
    suspend fun exportPdf(destination: Uri)

    /** Existing image export implementation retained for future use. */
    suspend fun exportImages(treeUri: Uri, onProgress: (done: Int, total: Int) -> Unit): Int

    /** Anything this format cannot carry through an export, shown before it runs. */
    val exportCaveat: String?
}

/** Back arrow that matches the platform's own. */
@Composable
fun BackArrow(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(R.drawable.ic_arrow_back),
            contentDescription = "Back",
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Overflow menu: share, export to PDF, and download the original. Owns the SAF launchers and
 * the progress/result dialogs so both viewers behave identically.
 */
@Composable
fun DocumentOverflowMenu(
    documentUri: Uri,
    displayName: String,
    mimeType: String,
    target: ExportTarget,
    /**
     * Whether to offer "Export to PDF". The PDF viewer does not: the document already
     * is a PDF, so "Download" covers it. DOCX and XLSX do, because for them it
     * genuinely generates one.
     */
    showExportPdf: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var expanded by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<ExportState>(ExportState.Idle) }
    var caveat by remember { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val baseName = remember(displayName) {
        displayName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "document" }
    }

    val pdfDestination = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { destination ->
        if (destination == null) {
            state = ExportState.Idle // user cancelled the picker
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            state = ExportState.Working("Exporting PDF")
            state = try {
                target.exportPdf(destination)
                ExportState.Done("PDF saved")
            } catch (t: Throwable) {
                ExportState.Failed(t.message ?: "The PDF could not be written")
            }
        }
    }

    // "Download" â€” the system Save As dialog, writing the original file unchanged.
    val downloadDestination = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(mimeType),
    ) { destination ->
        if (destination == null) {
            state = ExportState.Idle // user cancelled Save As
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            state = ExportState.Working("Saving document")
            state = try {
                saveOriginalTo(context, documentUri, destination)
                ExportState.Done("Saved")
            } catch (t: Throwable) {
                ExportState.Failed(t.message ?: "The file could not be saved")
            }
        }
    }

    fun confirmThen(action: () -> Unit) {
        val note = target.exportCaveat
        if (note == null) action() else { caveat = note; pendingAction = action }
    }

    IconButton(onClick = { expanded = true }) {
        Icon(
            painter = painterResource(R.drawable.ic_more_vert),
            contentDescription = "More options",
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }

    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            leadingIcon = { Icon(painterResource(R.drawable.ic_share), contentDescription = null) },
            text = { Text("Share") },
            onClick = {
                expanded = false
                scope.launch {
                    try {
                        val intent = shareDocument(context, documentUri, displayName, mimeType)
                        context.startActivity(Intent.createChooser(intent, "Share document"))
                    } catch (t: Throwable) {
                        state = ExportState.Failed(t.message ?: "The document could not be shared")
                    }
                }
            },
        )
        if (showExportPdf) {
            DropdownMenuItem(
                leadingIcon = { Icon(painterResource(R.drawable.ic_pdf), contentDescription = null) },
                text = { Text("Export to PDF") },
                onClick = {
                    expanded = false
                    confirmThen { pdfDestination.launch("$baseName.pdf") }
                },
            )
        }
        DropdownMenuItem(
            leadingIcon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null) },
            text = { Text("Download") },
            onClick = {
                expanded = false
                // Saving the original needs no caveat: nothing is re-rendered.
                downloadDestination.launch(displayName)
            },
        )
    }

    // Caveat shown *before* exporting, so nothing is produced under a false promise.
    caveat?.let { note ->
        AlertDialog(
            onDismissRequest = { caveat = null; pendingAction = null },
            title = { Text("Before exporting") },
            text = { Text(note) },
            confirmButton = {
                TextButton(onClick = {
                    val action = pendingAction
                    caveat = null
                    pendingAction = null
                    action?.invoke()
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { caveat = null; pendingAction = null }) { Text("Cancel") }
            },
        )
    }

    when (val current = state) {
        is ExportState.Working -> AlertDialog(
            onDismissRequest = { },
            title = { Text(current.label) },
            text = {
                androidx.compose.foundation.layout.Column {
                    if (current.total > 0) {
                        Text("Page ${current.done} of ${current.total}")
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { current.done.toFloat() / current.total.toFloat() },
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.width(20.dp).height(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(14.dp))
                            Text("Workingâ€¦")
                        }
                    }
                }
            },
            confirmButton = { },
        )

        is ExportState.Done -> AlertDialog(
            onDismissRequest = { state = ExportState.Idle },
            title = { Text("Export complete") },
            text = { Text(current.message) },
            confirmButton = { TextButton(onClick = { state = ExportState.Idle }) { Text("OK") } },
        )

        is ExportState.Failed -> AlertDialog(
            onDismissRequest = { state = ExportState.Idle },
            title = { Text("Export failed") },
            text = { Text(current.message) },
            confirmButton = { TextButton(onClick = { state = ExportState.Idle }) { Text("OK") } },
        )

        ExportState.Idle -> Unit
    }
}

/**
 * The floating "Page X / Y" indicator.
 *
 * [approximate] is set by the DOCX viewer, where pagination comes from the HTML
 * renderer's own geometry rather than from Word.
 */
@Composable
fun PageCounterPill(
    current: Int,
    total: Int,
    approximate: Boolean,
    modifier: Modifier = Modifier,
) {
    if (total <= 0) return
    Surface(
        modifier = modifier.padding(bottom = 18.dp),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 4.dp,
        shadowElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Page $current / $total",
                style = MaterialTheme.typography.labelLarge,
            )
            if (approximate) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "(approx.)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
