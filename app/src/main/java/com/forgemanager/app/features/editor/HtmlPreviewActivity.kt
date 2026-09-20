package com.forgemanager.app.features.editor

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLConnection

class HtmlPreviewActivity : Activity() {
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

    @Deprecated("Android back compatibility")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)
        val tools = HorizontalScrollView(this@HtmlPreviewActivity).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(this@HtmlPreviewActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.BLACK)
                addView(tool("←") { onBackPressed() })
                addView(tool("→") { if (webView.canGoForward()) webView.goForward() })
                addView(tool("↻") { load() })
                addView(tool("−") { webView.zoomOut() })
                addView(tool("+") { webView.zoomIn() })
                addView(tool("FIND") { showFind() })
                addView(tool("SOURCE") { openSource() })
                addView(tool("SHARE") { share() })
                addView(tool("COPY") { copyAddress() })
            })
        }
        addView(tools, LinearLayout.LayoutParams(-1, dp(48)))
        title = TextView(this@HtmlPreviewActivity).apply {
            text = name
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(6, 6, 6))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            maxLines = 2
        }
        addView(title, LinearLayout.LayoutParams(-1, dp(44)))
        webView = WebView(this@HtmlPreviewActivity).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.blockNetworkLoads = true
            settings.loadsImagesAutomatically = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return true
                    if (uri.host == LOCAL_HOST && uri.scheme == "https") return false
                    if (uri.scheme == "data" || uri.scheme == "about") return false
                    showExternalLink(uri)
                    return true
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val uri = request?.url ?: return blocked()
                    return when {
                        uri.host == LOCAL_HOST && uri.scheme == "https" -> localResource(uri)
                        uri.scheme == "data" || uri.scheme == "about" -> null
                        else -> blocked()
                    }
                }
            }
        }
        addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun tool(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 10f
        setTextColor(Color.rgb(0, 190, 255))
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { action() }
    }

    private fun load() {
        title.text = "Abrindo $name…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val backend = graph.resolver.backendFor(location)
                    val node = backend.stat(location)
                    require(node.size <= MAX_HTML_BYTES) { "Arquivo grande demais para prévia HTML" }
                    backend.openInput(location).bufferedReader(Charsets.UTF_8).use { reader ->
                        val result = StringBuilder()
                        val buffer = CharArray(32 * 1024)
                        while (true) {
                            val n = reader.read(buffer)
                            if (n < 0) break
                            result.append(buffer, 0, n)
                            require(result.length <= MAX_HTML_CHARS) { "HTML excede o limite de prévia" }
                        }
                        result.toString()
                    }
                }
            }.onSuccess { html ->
                title.text = "$name • WebView Forge • JS/rede bloqueados"
                webView.loadDataWithBaseURL("https://$LOCAL_HOST/", html, "text/html", "UTF-8", null)
            }.onFailure { showError(it.message ?: "Falha ao visualizar HTML") }
        }
    }

    private fun localResource(uri: Uri): WebResourceResponse {
        val relative = Uri.decode(uri.encodedPath.orEmpty()).trimStart('/')
        if (!isSafeRelative(relative)) return blocked()
        return try {
            val child = resolveSibling(relative) ?: return blocked()
            val backend = runBlocking(Dispatchers.IO) { graph.resolver.backendFor(child) }
            val node = runBlocking(Dispatchers.IO) { backend.stat(child) }
            if (node.isDirectory || node.size > MAX_RESOURCE_BYTES) return blocked()
            val stream = runBlocking(Dispatchers.IO) { backend.openInput(child) }
            val mime = node.mimeType ?: URLConnection.guessContentTypeFromName(relative)
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(relative.substringAfterLast('.', ""))
                ?: "application/octet-stream"
            WebResourceResponse(mime, if (mime.startsWith("text/")) "UTF-8" else null, stream)
        } catch (_: Throwable) {
            blocked()
        }
    }

    private fun resolveSibling(relative: String): FileLocation? = when (val base = location) {
        is FileLocation.Direct -> {
            val parent = File(base.path).parentFile ?: return null
            val canonicalParent = parent.canonicalFile
            val child = File(parent, relative).canonicalFile
            if (child.path != canonicalParent.path && !child.path.startsWith(canonicalParent.path + File.separator)) null
            else FileLocation.Direct(child.path)
        }
        is FileLocation.Archive -> {
            val parent = base.entryPath.substringBeforeLast('/', "")
            val path = listOf(parent, relative).filter { it.isNotBlank() }.joinToString("/")
            val normalized = normalizeArchive(path) ?: return null
            FileLocation.Archive(base.archivePath, normalized)
        }
        is FileLocation.Saf -> null
    }

    private fun normalizeArchive(path: String): String? {
        val stack = ArrayDeque<String>()
        for (segment in path.replace('\\', '/').split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (stack.isEmpty()) return null else stack.removeLast()
                else -> stack.addLast(segment)
            }
        }
        return stack.joinToString("/")
    }

    private fun isSafeRelative(value: String): Boolean =
        value.length <= 4096 && !value.contains('\u0000') && !value.startsWith('/') && !value.contains("\\")

    private fun showFind() {
        val input = EditText(this).apply { hint = "Buscar na página"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("Localizar").setView(input)
            .setPositiveButton("Buscar") { _, _ -> webView.findAllAsync(input.text.toString()) }
            .setNeutralButton("Próximo") { _, _ -> webView.findNext(true) }
            .setNegativeButton("Fechar", null).show()
    }

    private fun openSource() {
        startActivity(Intent(this, TextEditorActivity::class.java).putFileLocation(location, name))
    }

    private fun share() {
        val text = "${name}\n${location.displayPath}"
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Compartilhar página"))
    }

    private fun copyAddress() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Forge WebView", location.displayPath))
        Toast.makeText(this, "Caminho copiado", Toast.LENGTH_SHORT).show()
    }

    private fun showExternalLink(uri: Uri) {
        AlertDialog.Builder(this).setTitle("Link externo")
            .setMessage("A prévia local não abre rede automaticamente. Abrir este link no navegador do sistema?\n\n$uri")
            .setPositiveButton("Abrir") { _, _ -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) } }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

    private fun showError(message: String) = AlertDialog.Builder(this)
        .setTitle("Forge WebView").setMessage(message).setPositiveButton("OK", null).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val LOCAL_HOST = "forge.local"
        private const val MAX_HTML_BYTES = 8L * 1024 * 1024
        private const val MAX_HTML_CHARS = 8 * 1024 * 1024
        private const val MAX_RESOURCE_BYTES = 64L * 1024 * 1024
    }
}
