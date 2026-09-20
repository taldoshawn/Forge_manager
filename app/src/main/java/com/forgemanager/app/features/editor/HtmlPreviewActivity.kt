package com.forgemanager.app.features.editor

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.forgemanager.app.ForgeApplication
import com.forgemanager.app.core.file.FileLocation
import com.forgemanager.app.core.file.fileDisplayName
import com.forgemanager.app.core.file.putFileLocation
import com.forgemanager.app.core.file.readFileLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

/** Forge WebView: local HTML preview with explicit capabilities and no silent network/file access. */
class HtmlPreviewActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }
    private lateinit var location: FileLocation
    private lateinit var name: String
    private lateinit var webView: WebView
    private lateinit var title: TextView
    private var htmlSource = ""
    private var javascriptForSession = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        location = intent.readFileLocation() ?: run { finish(); return }
        name = intent.fileDisplayName() ?: location.displayPath.substringAfterLast('/').ifBlank { "HTML" }
        setContentView(buildUi())
        load()
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.clearHistory()
        webView.removeAllViews()
        webView.destroy()
        scope.cancel()
        super.onDestroy()
    }

    @Deprecated("Android back compatibility")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)
        val bar = LinearLayout(this@HtmlPreviewActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            setBackgroundColor(Color.rgb(5, 8, 12))
        }
        bar.addView(button("←", "Voltar") { onBackPressed() })
        bar.addView(button("→", "Avançar") { if (webView.canGoForward()) webView.goForward() })
        title = TextView(this@HtmlPreviewActivity).apply {
            text = name
            setTextColor(Color.WHITE)
            maxLines = 2
        }
        bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(button("↻", "Recarregar") { load() })
        bar.addView(button("⋮", "Opções") { anchor -> showMenu(anchor) })
        addView(bar, LinearLayout.LayoutParams(-1, dp(54)))

        webView = WebView(this@HtmlPreviewActivity).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            settings.databaseEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.blockNetworkLoads = true
            settings.loadsImagesAutomatically = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val scheme = request?.url?.scheme.orEmpty()
                    return scheme !in setOf("about", "data")
                }
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val uri = request?.url ?: return blocked()
                    return if (uri.scheme == "data" || uri.scheme == "about") null else blocked()
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    title.text = "$name  •  Forge WebView${if (javascriptForSession) " • JS" else ""}"
                }
            }
        }
        addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun button(label: String, description: String, action: (android.view.View) -> Unit) = Button(this).apply {
        text = label
        contentDescription = description
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        minWidth = dp(44)
        setOnClickListener { action(it) }
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
                htmlSource = html
                title.text = "$name  •  Forge WebView"
                webView.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null)
            }.onFailure { showError(it.message ?: "Falha ao visualizar HTML") }
        }
    }

    private fun showMenu(anchor: android.view.View) {
        PopupMenu(this, anchor).apply {
            menu.add("Editar código")
            menu.add("Compartilhar arquivo")
            menu.add("Localizar na página")
            menu.add("Copiar HTML")
            menu.add(if (javascriptForSession) "Desativar JavaScript" else "Ativar JavaScript nesta sessão")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Editar código" -> startActivity(Intent(this@HtmlPreviewActivity, TextEditorActivity::class.java).putFileLocation(location, name))
                    "Compartilhar arquivo" -> share()
                    "Localizar na página" -> findInPage()
                    "Copiar HTML" -> {
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(name, htmlSource))
                        Toast.makeText(this@HtmlPreviewActivity, "HTML copiado", Toast.LENGTH_SHORT).show()
                    }
                    "Ativar JavaScript nesta sessão" -> confirmJavaScript()
                    "Desativar JavaScript" -> { javascriptForSession = false; webView.settings.javaScriptEnabled = false; load() }
                }
                true
            }
            show()
        }
    }

    private fun confirmJavaScript() {
        AlertDialog.Builder(this).setTitle("Executar JavaScript local?")
            .setMessage("Ative apenas para um arquivo em que confia. A rede e o acesso a arquivos do aparelho continuarão bloqueados nesta WebView.")
            .setPositiveButton("Ativar nesta sessão") { _, _ ->
                javascriptForSession = true
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                load()
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun findInPage() {
        val input = EditText(this).apply { hint = "Texto na página"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Localizar").setView(input)
            .setPositiveButton("Buscar") { _, _ -> webView.findAllAsync(input.text.toString()) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun share() {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { materializeForShare() } }
                .onSuccess { file ->
                    val uri = FileProvider.getUriForFile(this@HtmlPreviewActivity, "$packageName.files", file)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/html")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Compartilhar HTML"))
                }.onFailure { showError(it.message ?: "Não foi possível compartilhar") }
        }
    }

    private fun materializeForShare(): File {
        val direct = (location as? FileLocation.Direct)?.path?.let(::File)
        if (direct != null && direct.isFile && direct.canRead()) return direct
        val dir = File(cacheDir, "shared").apply { mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9._() -]"), "_").take(120).ifBlank { "page.html" }
        val out = File(dir, "${System.nanoTime()}-$safe")
        val backend = graph.resolver.backendFor(location)
        backend.openInput(location).use { input -> FileOutputStream(out).use { input.copyTo(it, 64 * 1024) } }
        return out
    }

    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

    private fun showError(message: String) = AlertDialog.Builder(this)
        .setTitle("Forge WebView")
        .setMessage(message)
        .setPositiveButton("OK", null)
        .show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_HTML_BYTES = 8L * 1024 * 1024
        private const val MAX_HTML_CHARS = 8 * 1024 * 1024
    }
}
