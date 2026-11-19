package com.underrated.docreader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.underrated.docreader.pdf.OpenFailure
import com.underrated.docreader.pdf.OpenResult
import com.underrated.docreader.pdf.PdfEngine
import com.underrated.docreader.pdf.queryDisplayName
import com.underrated.docreader.ui.DocReaderTheme
import com.underrated.docreader.ui.DocxScreen
import com.underrated.docreader.ui.FullScreenLoader
import com.underrated.docreader.ui.OpenScreen
import com.underrated.docreader.ui.PasswordDialog
import com.underrated.docreader.ui.ViewerScreen
import com.underrated.docreader.ui.XlsxScreen
import com.underrated.docreader.viewer.PdfViewerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    /**
     * One thread for all rasterising. The platform PdfRenderer is not thread safe and
     * allows a single open page at a time, so extra threads would only contend on the
     * lock inside PdfRendererSource. Keeping it off the main thread is what matters.
     */
    private val renderExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pdf-render").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val renderDispatcher = renderExecutor.asCoroutineDispatcher()

    /** Tracked so system memory pressure can reach the live document. */
    private var activeState: PdfViewerState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val initialUri: Uri? = if (intent?.action == Intent.ACTION_VIEW) intent.data else null

        // Lets a test harness pick the engine up front:
        //   adb shell am start ... --es engine PDFIUM
        // The UI selector on the open screen is the normal way to choose.
        val initialEngine = intent?.getStringExtra("engine")
            ?.let { name -> PdfEngine.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
            ?: PdfEngine.Default

        setContent {
            DocReaderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    App(
                        initialUri = initialUri,
                        initialEngine = initialEngine,
                        onStateChanged = { activeState = it },
                        renderDispatcher = renderDispatcher,
                    )
                }
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            // Unlike the Collabora tile layer, which logs this and frees nothing, we
            // actually drop cached pixels here.
            activeState?.onTrimMemory()
        }
    }

    override fun onDestroy() {
        activeState?.dispose()
        activeState = null
        super.onDestroy()
        renderExecutor.shutdownNow()
    }
}

private const val DOCX_MIME =
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

private const val XLSX_MIME =
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

/** Word documents go to a different viewer; see DocxScreen for why. */
private fun isWordDocument(context: Context, uri: Uri): Boolean {
    if (context.contentResolver.getType(uri) == DOCX_MIME) return true
    return queryDisplayName(context, uri)?.endsWith(".docx", ignoreCase = true) == true
}

/** Spreadsheets get the grid viewer rather than a paged one. */
private fun isSpreadsheet(context: Context, uri: Uri): Boolean {
    if (context.contentResolver.getType(uri) == XLSX_MIME) return true
    return queryDisplayName(context, uri)?.endsWith(".xlsx", ignoreCase = true) == true
}

private sealed interface Screen {
    data object Picker : Screen
    data class Opening(val label: String) : Screen
    data class ViewingDocx(val uri: Uri, val title: String) : Screen
    data class ViewingXlsx(val uri: Uri, val title: String) : Screen
    data class Viewing(
        val state: PdfViewerState,
        val uri: Uri,
        val title: String,
        val engine: PdfEngine,
    ) : Screen
}

@Composable
private fun App(
    initialUri: Uri?,
    initialEngine: PdfEngine,
    onStateChanged: (PdfViewerState?) -> Unit,
    renderDispatcher: kotlinx.coroutines.CoroutineDispatcher,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf<Screen>(Screen.Picker) }
    var lastError by remember { mutableStateOf<String?>(null) }
    var openingJob by remember { mutableStateOf<Job?>(null) }
    val engine = PdfEngine.Default
    val history = remember { com.underrated.docreader.ui.RecentDocuments(context) }
    var recent by remember { mutableStateOf(history.read()) }

    // Set when an engine that supports passwords reports an encrypted file.
    var passwordFor by remember { mutableStateOf<Uri?>(null) }
    var passwordRetry by remember { mutableStateOf(false) }

    fun closeDocument() {
        openingJob?.cancel()
        openingJob = null
        (screen as? Screen.Viewing)?.state?.dispose()
        onStateChanged(null)
        screen = Screen.Picker
    }

    fun open(uri: Uri, password: String? = null) {
        openingJob?.cancel()
        (screen as? Screen.Viewing)?.state?.dispose()
        onStateChanged(null)
        lastError = null
        screen = Screen.Opening("Opening document")
        openingJob = scope.launch {
            // Persist picker grants when the provider supports them.
            // Best effort only: ACTION_OPEN_DOCUMENT does not request a persistable
            // grant by default, so this throws on most devices. The session grant is
            // all Phase 1 needs.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }

            val accessible = withContext(Dispatchers.IO) { runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.read() } ?: error("Unavailable")
            }.isSuccess }
            currentCoroutineContext().ensureActive()
            if (!accessible) {
                history.remove(uri.toString())
                recent = history.read()
                lastError = "This file is unavailable or access has expired. Use Open Document to select it again."
                screen = Screen.Picker
                return@launch
            }
            val name = queryDisplayName(context, uri) ?: "Document"
            history.add(uri, name)
            recent = history.read()
            if (withContext(Dispatchers.IO) { isWordDocument(context, uri) }) {
                currentCoroutineContext().ensureActive()
                onStateChanged(null)
                screen = Screen.ViewingDocx(uri, queryDisplayName(context, uri) ?: "Document")
                return@launch
            }

            if (withContext(Dispatchers.IO) { isSpreadsheet(context, uri) }) {
                currentCoroutineContext().ensureActive()
                onStateChanged(null)
                screen = Screen.ViewingXlsx(uri, queryDisplayName(context, uri) ?: "Workbook")
                return@launch
            }

            val chosen = engine
            var pendingSource: com.underrated.docreader.pdf.PdfSource? = null
            val result = try {
                withContext(Dispatchers.IO) {
                    chosen.open(context, uri, password).also { opened ->
                        if (opened is OpenResult.Success) pendingSource = opened.source
                    }
                }
            } catch (cancelled: CancellationException) {
                pendingSource?.close()
                throw cancelled
            }
            try {
                currentCoroutineContext().ensureActive()
            } catch (cancelled: CancellationException) {
                pendingSource?.close()
                throw cancelled
            }
            when (result) {
                is OpenResult.Success -> {
                    passwordFor = null
                    passwordRetry = false
                    val state = PdfViewerState(result.source, scope, renderDispatcher)
                    pendingSource = null
                    onStateChanged(state)
                    screen = Screen.Viewing(state, uri, result.displayName ?: "Document", chosen)
                    state.prepare()
                }

                is OpenResult.Failure -> {
                    onStateChanged(null)
                    screen = Screen.Picker
                    if (result.reason == OpenFailure.PASSWORD_PROTECTED && chosen.supportsPassword) {
                        // Recoverable: ask and retry rather than giving up.
                        passwordRetry = password != null
                        passwordFor = uri
                    } else {
                        lastError = describe(result.reason, result.detail, chosen)
                    }
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) open(uri)
    }

    LaunchedEffect(initialUri) {
        if (initialUri != null) open(initialUri)
    }

    when (val current = screen) {
        is Screen.Picker -> OpenScreen(
            lastError = lastError,
            engine = engine,
            onEngineChange = {},
            recent = recent,
            onOpenRecent = { open(Uri.parse(it.uri)) },
            onPickDocument = { picker.launch(arrayOf("application/pdf", DOCX_MIME, XLSX_MIME)) },
        )

        is Screen.Opening -> Box(Modifier.fillMaxSize()) {
            OpenScreen(lastError = null, engine = engine, onEngineChange = {}, onPickDocument = {})
            FullScreenLoader(current.label)
        }

        is Screen.ViewingXlsx -> {
            BackHandler { closeDocument() }
            XlsxScreen(
                uri = current.uri,
                title = current.title,
                onClose = { closeDocument() },
            )
        }

        is Screen.ViewingDocx -> {
            BackHandler { closeDocument() }
            DocxScreen(
                uri = current.uri,
                title = current.title,
                onClose = { closeDocument() },
            )
        }

        is Screen.Viewing -> {
            BackHandler { closeDocument() }
            ViewerScreen(
                state = current.state,
                uri = current.uri,
                title = current.title,
                engineLabel = current.engine.label,
                onClose = { closeDocument() },
            )
        }
    }

    passwordFor?.let { uri ->
        PasswordDialog(
            fileName = queryDisplayName(context, uri),
            wrongPassword = passwordRetry,
            onDismiss = { passwordFor = null; passwordRetry = false },
            onSubmit = { entered -> passwordFor = null; open(uri, entered) },
        )
    }
}

private fun describe(reason: OpenFailure, detail: String?, engine: PdfEngine): String = when (reason) {
    OpenFailure.PASSWORD_PROTECTED ->
        "This PDF is password protected, and ${engine.label} cannot open encrypted " +
            "files. Switch the rendering engine to PDFium and try again."

    OpenFailure.CORRUPT_OR_UNSUPPORTED ->
        "This file could not be opened. It may be damaged, or not a PDF." +
            (detail?.let { "\n\n$it" } ?: "")

    OpenFailure.UNREADABLE ->
        "The file could not be read. The app may have lost access to it." +
            (detail?.let { "\n\n$it" } ?: "")
}
