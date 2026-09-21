package com.forgemanager.app.features.browser

import android.Manifest
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.forgemanager.app.core.ui.ForgeActivity
import com.forgemanager.app.features.settings.UiPreferences
import java.util.Locale

class ForgeBrowserActivity : ForgeActivity() {
    private lateinit var web: WebView
    private lateinit var address: EditText
    private var desktop = false
    private var pendingDownload: DownloadSpec? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        if (savedInstanceState == null) {
            load(intent.getStringExtra(EXTRA_URL) ?: DEFAULT_HOME)
        } else {
            web.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        web.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Android back compatibility")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_DOWNLOAD_STORAGE) return
        val spec = pendingDownload
        pendingDownload = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && spec != null) {
            enqueueDownload(spec)
        } else {
            toast("Permissão de armazenamento negada")
        }
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            web.stopLoading()
            web.loadUrl("about:blank")
            web.clearHistory()
            web.removeAllViews()
            web.destroy()
        }
        super.onDestroy()
    }

    private fun buildUi() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(UiPreferences.background(this@ForgeBrowserActivity))

        val bar = LinearLayout(this@ForgeBrowserActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(UiPreferences.surface(this@ForgeBrowserActivity))
        }
        bar.addView(button("←") { if (web.canGoBack()) web.goBack() else finish() })
        bar.addView(button("→") { if (web.canGoForward()) web.goForward() })
        bar.addView(button("↻") { web.reload() })
        address = EditText(this@ForgeBrowserActivity).apply {
            setSingleLine()
            textSize = 12.5f
            setTextColor(UiPreferences.textPrimary(this@ForgeBrowserActivity))
            setHintTextColor(UiPreferences.textSecondary(this@ForgeBrowserActivity))
            setBackgroundColor(UiPreferences.elevatedSurface(this@ForgeBrowserActivity))
            hint = "https://"
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> load(text.toString()); true }
        }
        bar.addView(address, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(button("⋮") { showMenu() })
        addView(bar, LinearLayout.LayoutParams(-1, dp(54)))

        web = WebView(this@ForgeBrowserActivity).apply {
            setBackgroundColor(UiPreferences.background(this@ForgeBrowserActivity))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                setGeolocationEnabled(false)
                mediaPlaybackRequiresUserGesture = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
            }
            WebView.setWebContentsDebuggingEnabled(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return true
                    return if (isAllowedWebUri(uri)) false else {
                        toast("Esquema bloqueado no navegador interno")
                        true
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    address.setText(url.orEmpty())
                }

                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    handler?.cancel()
                    toast("Certificado HTTPS inválido — conexão bloqueada", long = true)
                }
            }
            setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                requestDownload(url, userAgent, contentDisposition, mimeType, contentLength)
            }
        }
        addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(UiPreferences.textPrimary(this@ForgeBrowserActivity))
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { action() }
    }

    private fun load(raw: String) {
        val normalized = normalizeTypedUrl(raw) ?: run {
            toast("URL inválida. Use HTTP ou HTTPS.")
            return
        }
        web.loadUrl(normalized)
    }

    private fun normalizeTypedUrl(raw: String): String? {
        var value = raw.trim()
        if (value.isBlank()) return null
        if (!value.contains("://")) value = "https://$value"
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        return if (scheme == "http") uri.buildUpon().scheme("https").build().toString() else uri.toString()
    }

    private fun isAllowedWebUri(uri: Uri): Boolean =
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()

    private fun requestDownload(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) {
        val raw = url ?: return
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        if (uri == null || !isAllowedWebUri(uri)) {
            toast("Download bloqueado: URL inválida")
            return
        }
        val guessed = URLUtil.guessFileName(raw, contentDisposition, mimeType)
        val safeName = sanitizeDownloadName(guessed)
        val spec = DownloadSpec(raw, userAgent, mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream", safeName)
        val size = if (contentLength > 0) "\nTamanho: ${formatBytes(contentLength)}" else ""
        AlertDialog.Builder(this)
            .setTitle("Baixar arquivo?")
            .setMessage("$safeName$size\nDestino: /storage/emulated/0/Download")
            .setPositiveButton("Baixar") { _, _ -> prepareDownload(spec) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun prepareDownload(spec: DownloadSpec) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = spec
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_DOWNLOAD_STORAGE)
            return
        }
        enqueueDownload(spec)
    }

    private fun enqueueDownload(spec: DownloadSpec) {
        runCatching {
            val request = DownloadManager.Request(Uri.parse(spec.url))
                .setTitle(spec.fileName)
                .setDescription("Forge Manager • Download")
                .setMimeType(spec.mimeType)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, spec.fileName)
            spec.userAgent?.takeIf { it.isNotBlank() }?.let { request.addRequestHeader("User-Agent", it) }
            CookieManager.getInstance().getCookie(spec.url)?.takeIf { it.isNotBlank() }?.let {
                request.addRequestHeader("Cookie", it)
            }
            getSystemService(DownloadManager::class.java).enqueue(request)
        }.onSuccess {
            toast("Download iniciado: ${spec.fileName}", long = true)
        }.onFailure {
            toast("Não foi possível iniciar o download: ${it.message ?: "erro"}", long = true)
        }
    }

    private fun sanitizeDownloadName(value: String): String {
        val cleaned = value
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .trim('.')
            .take(180)
        return cleaned.ifBlank { "download-${System.currentTimeMillis()}" }
    }

    private fun showMenu() {
        val items = arrayOf(
            "Compartilhar",
            "Copiar URL",
            "Localizar na página",
            "Downloads",
            "Modo ${if (desktop) "mobile" else "desktop"}",
            "Abrir externamente"
        )
        AlertDialog.Builder(this).setTitle("Forge Web").setItems(items) { _, which ->
            when (which) {
                0 -> web.url?.let { url ->
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), "Compartilhar"))
                }
                1 -> web.url?.let { url ->
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("URL", url))
                    toast("URL copiada")
                }
                2 -> {
                    val input = EditText(this)
                    AlertDialog.Builder(this).setTitle("Localizar").setView(input)
                        .setPositiveButton("Buscar") { _, _ -> web.findAllAsync(input.text.toString()) }
                        .setNegativeButton("Cancelar", null).show()
                }
                3 -> runCatching { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
                    .onFailure { toast("Tela de downloads indisponível") }
                4 -> {
                    desktop = !desktop
                    web.settings.userAgentString = if (desktop) {
                        WebSettings.getDefaultUserAgent(this).replace("Mobile", "Desktop").replace("Android", "X11; Linux x86_64")
                    } else WebSettings.getDefaultUserAgent(this)
                    web.reload()
                }
                5 -> web.url?.let { raw ->
                    val uri = runCatching { Uri.parse(raw) }.getOrNull()
                    if (uri != null && isAllowedWebUri(uri)) {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                            .onFailure { toast("Nenhum aplicativo compatível") }
                    }
                }
            }
        }.show()
    }

    private fun formatBytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var size = value.toDouble()
        var index = -1
        do {
            size /= 1024.0
            index++
        } while (size >= 1024 && index < units.lastIndex)
        return String.format(Locale.US, "%.1f %s", size, units[index])
    }

    private fun toast(message: String, long: Boolean = false) =
        Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private data class DownloadSpec(
        val url: String,
        val userAgent: String?,
        val mimeType: String,
        val fileName: String
    )

    companion object {
        const val EXTRA_URL = "url"
        private const val DEFAULT_HOME = "https://www.google.com"
        private const val REQ_DOWNLOAD_STORAGE = 301
    }
}
