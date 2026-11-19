package com.underrated.docreader.docx

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * DOCX rendering for Phase 2, as a prototype.
 *
 * Word documents have no fixed page geometry the way a PDF does — they are laid out
 * from styles, section properties and flowed content. Rather than write a layout
 * engine, this hands the file to docx-preview (Apache-2.0), a JavaScript renderer
 * that produces the page boxes, paragraph styles, tables, numbering and images as
 * HTML/CSS, and displays the result in a WebView.
 *
 * Nothing leaves the device:
 *  - the page and both scripts come from the APK's assets,
 *  - the document itself is streamed from the content URI the user picked,
 *  - every other request is refused by [DocxWebViewClient].
 *
 * This is a rendering strategy, not a document conversion feature: no file is
 * written, nothing is exported, and the original is opened read-only.
 */
const val DOCX_ORIGIN = "https://docreader.local"

/** Serves the viewer page, its scripts and the document; blocks everything else. */
class DocxWebViewClient(
    private val context: Context,
    private val documentUri: Uri,
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        val url = request?.url ?: return blocked()
        if (url.scheme + "://" + url.authority != DOCX_ORIGIN) return blocked()

        return when (val path = url.path?.trimStart('/')) {
            "viewer.html" -> asset("docx/viewer.html", "text/html")
            "jszip.min.js" -> asset("docx/jszip.min.js", "application/javascript")
            "docx-preview.min.js" -> asset("docx/docx-preview.min.js", "application/javascript")
            "document.bin" -> document()
            else -> {
                android.util.Log.w(TAG, "blocked request for $path")
                blocked()
            }
        }
    }

    private fun asset(path: String, mime: String): WebResourceResponse? = try {
        WebResourceResponse(mime, "utf-8", context.assets.open(path))
    } catch (io: IOException) {
        android.util.Log.e(TAG, "missing asset $path", io)
        null
    }

    private fun document(): WebResourceResponse? = try {
        val stream = context.contentResolver.openInputStream(documentUri)
        if (stream == null) {
            blocked()
        } else {
            WebResourceResponse(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                null,
                stream,
            )
        }
    } catch (t: Throwable) {
        android.util.Log.e(TAG, "could not open document", t)
        blocked()
    }

    /** An empty 403 — the viewer is offline by construction. */
    private fun blocked() = WebResourceResponse(
        "text/plain",
        "utf-8",
        403,
        "Blocked",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )

    private companion object {
        const val TAG = "DocxWebView"
    }
}

@SuppressLint("SetJavaScriptEnabled")
fun createDocxWebView(
    context: Context,
    documentUri: Uri,
    onRendered: (pages: Int) -> Unit,
    onPage: (current: Int, total: Int) -> Unit,
    onError: (message: String) -> Unit,
): WebView {
    lateinit var web: WebView
    val startedAt = android.os.SystemClock.elapsedRealtime()

    /** Page width reported by the renderer, kept so the fit can be redone on resize. */
    var pageWidth = 0

    fun applyFit() {
        val density = context.resources.displayMetrics.density
        val availableCss = web.width / density
        if (pageWidth <= 0 || availableCss <= 0f) return
        val zoom = ((availableCss - 8f) / pageWidth).coerceAtMost(1f)
        // __setZoom keeps the reading position; see viewer.html.
        if (zoom > 0f) web.evaluateJavascript("window.__setZoom($zoom);", null)
    }

    /**
     * The page cannot measure the screen: a WebView's layout viewport silently
     * expands to the widest content, so from inside the page `clientWidth` reports
     * the Word page width (816 css px), not the device. The page therefore reports
     * its page width out, and the native side — which does know how wide it is —
     * applies the fit-to-width zoom.
     */
    val bridge = object {
        @JavascriptInterface
        fun onRendered(pages: Int, pageWidthCss: Int) {
            web.post {
                pageWidth = pageWidthCss
                applyFit()
                android.util.Log.i(
                    PERF_TAG,
                    "docx rendered: $pages section(s), page width ${pageWidthCss}css, " +
                        "${android.os.SystemClock.elapsedRealtime() - startedAt} ms",
                )
                onRendered(pages)
            }
        }

        @JavascriptInterface
        fun onPage(current: Int, total: Int) {
            web.post { onPage(current, total) }
        }

        @JavascriptInterface
        fun onError(message: String) {
            web.post {
                android.util.Log.w(PERF_TAG, "docx failed: $message")
                onError(message)
            }
        }
    }

    web = buildWebView(context, documentUri, bridge)

    // Rotation and multi-window resize the view without reloading the page, so the
    // fit-to-width zoom has to be recomputed against the new width.
    var lastWidth = 0
    web.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
        val width = right - left
        if (width != lastWidth) {
            lastWidth = width
            web.post { applyFit() }
        }
    }
    return web
}

private const val PERF_TAG = "DocReaderPerf"

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
private fun buildWebView(
    context: Context,
    documentUri: Uri,
    bridge: Any,
): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true // required: the renderer is JavaScript
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.blockNetworkLoads = true
    settings.builtInZoomControls = true // pinch zoom, for free
    settings.displayZoomControls = false
    settings.setSupportZoom(true)
    // Required for the page's <meta name="viewport" content="width=device-width"> to
    // be honoured. Without it the layout viewport silently expands to the widest
    // content (a Word page is 816 CSS px), and the page cannot measure the screen.
    settings.useWideViewPort = true

    addJavascriptInterface(bridge, "DocReader")
    webViewClient = DocxWebViewClient(context.applicationContext, documentUri)
    webChromeClient = object : android.webkit.WebChromeClient() {
        override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
            android.util.Log.i("DocxWebView", "console: ${message.message()}")
            return true
        }
    }
    loadUrl("$DOCX_ORIGIN/viewer.html")
}
