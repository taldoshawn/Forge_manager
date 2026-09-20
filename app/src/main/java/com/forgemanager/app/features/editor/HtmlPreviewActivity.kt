package com.forgemanager.app.features.editor

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.forgemanager.app.ForgeApplication
import com.forgemanager.app.core.file.FileLocation
import com.forgemanager.app.core.file.fileDisplayName
import com.forgemanager.app.core.file.readFileLocation
import com.forgemanager.app.core.ui.ForgeActivity
import com.forgemanager.app.features.settings.UiPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

class HtmlPreviewActivity : ForgeActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }
    private lateinit var location: FileLocation
    private lateinit var name: String
    private lateinit var webView: WebView
    private lateinit var title: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        location = intent.readFileLocation() ?: run { finish(); return }
        name = intent.fileDisplayName() ?: location.displayPath.substringAfterLast('/').ifBlank { "HTML" }
        setContentView(buildUi())
        load()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(UiPreferences.background(this@HtmlPreviewActivity))
        val bar = LinearLayout(this@HtmlPreviewActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(8), 0)
            setBackgroundColor(UiPreferences.surface(this@HtmlPreviewActivity))
        }
        bar.addView(Button(this@HtmlPreviewActivity).apply {
            text = "←"
            setTextColor(UiPreferences.textPrimary(this@HtmlPreviewActivity))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener { finish() }
        })
        title = TextView(this@HtmlPreviewActivity).apply {
            text = name
            setTextColor(UiPreferences.textPrimary(this@HtmlPreviewActivity))
            maxLines = 2
        }
        bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(Button(this@HtmlPreviewActivity).apply {
            text = "↻"
            contentDescription = "Recarregar"
            setTextColor(UiPreferences.textPrimary(this@HtmlPreviewActivity))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener { load() }
        })
        addView(bar, LinearLayout.LayoutParams(-1, dp(54)))
        webView = WebView(this@HtmlPreviewActivity).apply {
            setBackgroundColor(UiPreferences.background(this@HtmlPreviewActivity))
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            settings.databaseEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.blockNetworkLoads = true
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val uri = request?.url ?: return blocked()
                    return if (uri.scheme == "data" || uri.scheme == "about") null else blocked()
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                @Deprecated("Compatibility")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
            }
        }
        addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun load() {
        title.text = "Abrindo $name…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val backend = graph.resolver.backendFor(location)
                    val node = backend.stat(location)
                    if (node.size > MAX_PREVIEW_BYTES) error("Arquivo grande demais para prévia")
                    backend.openInput(location).bufferedReader(Charsets.UTF_8).use { reader ->
                        val result = StringBuilder()
                        val buffer = CharArray(32 * 1024)
                        while (true) {
                            val n = reader.read(buffer)
                            if (n < 0) break
                            result.append(buffer, 0, n)
                            if (result.length > MAX_PREVIEW_CHARS) error("Conteúdo excede o limite de prévia")
                        }
                        result.toString()
                    }
                }
            }.onSuccess { source ->
                val markdown = name.substringAfterLast('.', "").lowercase() in MARKDOWN_EXTENSIONS
                val html = if (markdown) renderMarkdown(source) else source
                title.text = "$name  •  prévia segura"
                webView.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null)
            }.onFailure { showError(it.message ?: "Falha ao visualizar") }
        }
    }

    private fun renderMarkdown(source: String): String {
        val body = StringBuilder()
        var inCode = false
        var inList = false
        for (raw in source.lines()) {
            if (raw.trim().startsWith("```")) {
                if (inList) { body.append("</ul>"); inList = false }
                body.append(if (inCode) "</code></pre>" else "<pre><code>")
                inCode = !inCode
                continue
            }
            if (inCode) {
                body.append(escape(raw)).append('\n')
                continue
            }
            val line = raw.trimEnd()
            val heading = Regex("^(#{1,6})\\s+(.+)$").find(line)
            if (heading != null) {
                if (inList) { body.append("</ul>"); inList = false }
                val level = heading.groupValues[1].length
                body.append("<h$level>").append(inlineMarkdown(heading.groupValues[2])).append("</h$level>")
                continue
            }
            if (line.startsWith("- ") || line.startsWith("* ")) {
                if (!inList) { body.append("<ul>"); inList = true }
                val item = line.drop(2)
                body.append("<li>").append(inlineMarkdown(item)).append("</li>")
                continue
            }
            if (inList) { body.append("</ul>"); inList = false }
            when {
                line.isBlank() -> body.append("<br>")
                line.startsWith("> ") -> body.append("<blockquote>").append(inlineMarkdown(line.drop(2))).append("</blockquote>")
                else -> body.append("<p>").append(inlineMarkdown(line)).append("</p>")
            }
        }
        if (inList) body.append("</ul>")
        if (inCode) body.append("</code></pre>")
        return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>
            :root{color-scheme:dark}body{background:#101114;color:#f1f3f7;font:15px system-ui,sans-serif;padding:18px;line-height:1.55}a{color:#2a8fff}pre{overflow:auto;background:#1d1f24;padding:12px;border-radius:8px}code{font-family:monospace}blockquote{border-left:3px solid #4c5666;margin-left:0;padding-left:12px;color:#aeb4bf}img{max-width:100%}
            </style></head><body>${body}</body></html>"""
    }

    private fun inlineMarkdown(value: String): String {
        var out = escape(value)
        out = Regex("`([^`]+)`").replace(out, "<code>$1</code>")
        out = Regex("\\*\\*([^*]+)\\*\\*").replace(out, "<strong>$1</strong>")
        out = Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)").replace(out, "<em>$1</em>")
        out = Regex("\\[([^]]+)]\\(([^)]+)\\)").replace(out) { match ->
            val label = match.groupValues[1]
            val href = match.groupValues[2]
            "<a href=\"${escapeAttribute(href)}\">$label</a>"
        }
        return out
    }

    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun escapeAttribute(value: String): String = escape(value).replace("'", "&#39;")
    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

    private fun showError(message: String) = AlertDialog.Builder(this)
        .setTitle("Prévia segura")
        .setMessage(message)
        .setPositiveButton("OK", null)
        .show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_PREVIEW_BYTES = 8L * 1024 * 1024
        private const val MAX_PREVIEW_CHARS = 8 * 1024 * 1024
        private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdown", "mkd")
    }
}
