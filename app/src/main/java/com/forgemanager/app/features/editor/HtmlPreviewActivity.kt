package com.forgemanager.app.features.editor

import com.forgemanager.app.core.ui.ForgeActivity

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
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
        webView.stopLoading()
        webView.destroy()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val bar = LinearLayout(this@HtmlPreviewActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(8), 0)
            setBackgroundColor(Color.rgb(5, 5, 5))
        }
        bar.addView(Button(this@HtmlPreviewActivity).apply {
            text = "←"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        })
        title = TextView(this@HtmlPreviewActivity).apply {
            text = name
            setTextColor(Color.WHITE)
            maxLines = 2
        }
        bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(Button(this@HtmlPreviewActivity).apply {
            text = "↻"
            contentDescription = "Recarregar"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { load() }
        })
        addView(bar, LinearLayout.LayoutParams(-1, dp(54)))
        webView = WebView(this@HtmlPreviewActivity).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
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
                    if (node.size > MAX_HTML_BYTES) error("Arquivo grande demais para prévia HTML")
                    backend.openInput(location).bufferedReader(Charsets.UTF_8).use { reader ->
                        val result = StringBuilder()
                        val buffer = CharArray(32 * 1024)
                        while (true) {
                            val n = reader.read(buffer)
                            if (n < 0) break
                            result.append(buffer, 0, n)
                            if (result.length > MAX_HTML_CHARS) error("HTML excede o limite de prévia")
                        }
                        result.toString()
                    }
                }
            }.onSuccess { html ->
                title.text = "$name  •  prévia segura"
                webView.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null)
            }.onFailure { showError(it.message ?: "Falha ao visualizar HTML") }
        }
    }

    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

    private fun showError(message: String) = AlertDialog.Builder(this)
        .setTitle("Prévia HTML")
        .setMessage(message)
        .setPositiveButton("OK", null)
        .show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_HTML_BYTES = 8L * 1024 * 1024
        private const val MAX_HTML_CHARS = 8 * 1024 * 1024
    }
}
