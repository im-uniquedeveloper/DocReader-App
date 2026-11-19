package com.underrated.docreader.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.underrated.docreader.export.openForWriting
import com.underrated.docreader.xlsx.SheetCanvas
import com.underrated.docreader.xlsx.SheetViewState
import com.underrated.docreader.xlsx.WorkbookResult
import com.underrated.docreader.xlsx.XlsxPrint
import com.underrated.docreader.xlsx.XlsxWorkbook
import com.underrated.docreader.xlsx.columnLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val XLSX_MIME_TYPE =
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

/** Stated up front, before any export runs. */
private const val XLSX_EXPORT_CAVEAT =
    "Exports are built from what this viewer renders.\n\n" +
        "Cell values, formatting, merged cells and the cached results of formulas are " +
        "included. Charts, pivot tables, images, macros, external links, data " +
        "validation and conditional formatting are not rendered and will be absent. " +
        "Formulas are never recalculated — Excel's last saved results are used."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XlsxScreen(
    uri: Uri,
    title: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density

    var workbook by remember { mutableStateOf<XlsxWorkbook?>(null) }
    var sheetIndex by remember { mutableIntStateOf(0) }
    var sheetState by remember { mutableStateOf<SheetViewState?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var truncated by remember { mutableStateOf(false) }

    val baseName = remember(title) { title.substringBeforeLast('.').ifBlank { "workbook" } }

    // Open once; sheets are parsed one at a time as tabs are selected.
    LaunchedEffect(uri) {
        loading = true
        var pendingWorkbook: XlsxWorkbook? = null
        val result = try {
            withContext(Dispatchers.IO) {
                XlsxWorkbook.open(context, uri).also { opened ->
                    if (opened is WorkbookResult.Success) pendingWorkbook = opened.workbook
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            pendingWorkbook?.close()
            throw cancelled
        }
        pendingWorkbook = null
        when (result) {
            is WorkbookResult.Success -> {
                workbook = result.workbook
                sheetIndex = 0
            }
            is WorkbookResult.Failure -> {
                error = result.message
                loading = false
            }
        }
    }

    LaunchedEffect(workbook, sheetIndex) {
        val book = workbook ?: return@LaunchedEffect
        loading = true
        try {
            val sheet = withContext(Dispatchers.IO) { book.loadSheet(sheetIndex) }
            truncated = sheet.truncated
            sheetState = SheetViewState(sheet, book::styleAt, scope, density)
            error = null
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w("XlsxScreen", "Could not parse worksheet ${sheetIndex + 1}", t)
            sheetState = null
            error = "This worksheet is damaged or exceeds the supported size limits."
        } finally {
            loading = false
        }
    }

    val workbookToClose = workbook
    DisposableEffect(workbookToClose) {
        onDispose { workbookToClose?.close() }
    }

    val target = remember(workbook, baseName) {
        object : ExportTarget {
            override suspend fun exportPdf(destination: Uri) {
                val book = workbook ?: error("The workbook is still loading")
                withContext(Dispatchers.IO) {
                    openForWriting(context.contentResolver, destination).use { fd ->
                        java.io.FileOutputStream(fd.fileDescriptor).use { out ->
                            XlsxPrint.writePdf(book, out)
                        }
                    }
                }
            }

            override suspend fun exportImages(
                treeUri: Uri,
                onProgress: (Int, Int) -> Unit,
            ): Int {
                val book = workbook ?: error("The workbook is still loading")
                return withContext(Dispatchers.IO) {
                    exportWorkbookImages(context, book, treeUri, baseName, onProgress)
                }
            }

            override val exportCaveat: String get() = XLSX_EXPORT_CAVEAT
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // This toolbar contains a two-line title. Use a compact height so
                // the worksheet tabs sit closer to the document header while the
                // normal system-bar insets and 48 dp tab touch targets remain intact.
                expandedHeight = 56.dp,
                navigationIcon = { BackArrow(onClick = onClose) },
                title = {
                    Column(
                        modifier = Modifier
                            .height(48.dp)
                            .padding(bottom = 4.dp),
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        val book = workbook
                        Text(
                            text = if (book != null) {
                                "Spreadsheet · ${book.sheets.size} sheet" +
                                    if (book.sheets.size == 1) "" else "s"
                            } else {
                                "Spreadsheet"
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
                        mimeType = XLSX_MIME_TYPE,
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
            Column(modifier = Modifier.fillMaxSize()) {
                val book = workbook
                if (book != null && book.sheets.size > 1) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .horizontalScroll(rememberScrollState())
                                .selectableGroup(),
                        ) {
                            book.sheets.forEachIndexed { index, ref ->
                                val selected = index == sheetIndex
                                Box(
                                    modifier = Modifier
                                        .height(48.dp)
                                        .width(120.dp)
                                        .selectable(
                                            selected = selected,
                                            onClick = { if (!selected) sheetIndex = index },
                                            role = Role.Tab,
                                        ),
                                    contentAlignment = Alignment.TopCenter,
                                ) {
                                    Text(
                                        ref.name,
                                        modifier = Modifier.padding(top = 4.dp, start = 12.dp, end = 12.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                    if (selected) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .fillMaxWidth()
                                                .height(2.dp)
                                                .background(MaterialTheme.colorScheme.primary),
                                        )
                                    }
                                }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    sheetState?.let { state ->
                        SheetCanvas(state = state, modifier = Modifier.fillMaxSize())

                        val (row, col) = state.firstVisibleCell()
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 14.dp),
                            shape = MaterialTheme.shapes.large,
                            tonalElevation = 4.dp,
                            shadowElevation = 3.dp,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        ) {
                            Text(
                                text = "${columnLabel(col)}${row + 1}  ·  " +
                                    "${state.sheet.rowCount} × ${state.sheet.columnCount}",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
            }

            if (loading) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Opening workbook", style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (truncated) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        text = "This sheet is very large and was truncated to keep the app responsive.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            error?.let { message ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(20.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 4.dp,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "This workbook could not be opened",
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

/** One PNG per planned print page, written into a SAF tree. */
private fun exportWorkbookImages(
    context: Context,
    workbook: XlsxWorkbook,
    treeUri: Uri,
    baseName: String,
    onProgress: (done: Int, total: Int) -> Unit,
): Int {
    val resolver = context.contentResolver
    val dirUri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )

    val counts = workbook.sheets.indices.map { index ->
        XlsxPrint.planSheet(workbook.loadSheet(index), index).size
    }
    val total = counts.sum()
    if (total == 0) error("This workbook has no printable content.")
    var written = 0
    for (index in workbook.sheets.indices) {
        val sheet = workbook.loadSheet(index)
        val plans = XlsxPrint.planSheet(sheet, index)
        for (plan in plans) {
            written++
            val bitmap = XlsxPrint.renderPage(workbook, sheet, plan, written, total)
            val name = "%s_%s_page_%03d.png".format(
                baseName,
                plan.sheetName.replace(Regex("[^A-Za-z0-9._-]"), "_"),
                written,
            )
            val fileUri = DocumentsContract.createDocument(resolver, dirUri, "image/png", name)
                ?: error("Could not create $name in the chosen folder")
            try {
                resolver.openOutputStream(fileUri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                } ?: error("Could not write $name")
            } finally {
                bitmap.recycle()
            }
            onProgress(written, total)
        }
    }
    return written
}
