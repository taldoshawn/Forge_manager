package com.forgemanager.app.features.browser

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.forgemanager.app.core.ui.ForgeActivity

class ForgeBrowserActivity : ForgeActivity() {
    private lateinit var web: WebView
    private lateinit var address: EditText
    private var desktop = false

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
        setBackgroundColor(Color.BLACK)

        val bar = LinearLayout(this@ForgeBrowserActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        bar.addView(button("←") { if (web.canGoBack()) web.goBack() else finish() })
        bar.addView(button("→") { if (web.canGoForward()) web.goForward() })
        bar.addView(button("↻") { web.reload() })
        address = EditText(this@ForgeBrowserActivity).apply {
            setSingleLine()
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "https://"
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> load(text.toString()); true }
        }
        bar.addView(address, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(button("⋮") { showMenu() })
        addView(bar, LinearLayout.LayoutParams(-1, dp(54)))

        web = WebView(this@ForgeBrowserActivity).apply {
            setBackgroundColor(Color.BLACK)
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
        }
        addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
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
        // Prefer HTTPS for manually typed HTTP URLs. Sites reached by an HTTPS page are still
        // governed by the WebView's mixed-content policy.
        return if (scheme == "http") uri.buildUpon().scheme("https").build().toString() else uri.toString()
    }

    private fun isAllowedWebUri(uri: Uri): Boolean =
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()

    private fun showMenu() {
        val items = arrayOf(
            "Compartilhar",
            "Copiar URL",
            "Localizar na página",
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
                3 -> {
                    desktop = !desktop
                    web.settings.userAgentString = if (desktop) {
                        WebSettings.getDefaultUserAgent(this).replace("Mobile", "Desktop").replace("Android", "X11; Linux x86_64")
                    } else WebSettings.getDefaultUserAgent(this)
                    web.reload()
                }
                4 -> web.url?.let { raw ->
                    val uri = runCatching { Uri.parse(raw) }.getOrNull()
                    if (uri != null && isAllowedWebUri(uri)) {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                            .onFailure { toast("Nenhum aplicativo compatível") }
                    }
                }
            }
        }.show()
    }

    private fun toast(message: String, long: Boolean = false) =
        Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_URL = "url"
        private const val DEFAULT_HOME = "https://www.google.com"
    }
}
