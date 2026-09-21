package com.forgemanager.app.features.viewer

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import com.forgemanager.app.ForgeApplication
import com.forgemanager.app.core.file.FileLocation
import com.forgemanager.app.core.file.fileDisplayName
import com.forgemanager.app.core.file.putFileLocation
import com.forgemanager.app.core.file.readFileLocation
import com.forgemanager.app.core.ui.ForgeActivity
import com.forgemanager.app.features.explorer.FileListAdapter
import com.forgemanager.app.features.settings.UiPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLConnection
import java.nio.ByteBuffer
import kotlin.math.max

class ImageViewerActivity : ForgeActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }
    private lateinit var location: FileLocation
    private lateinit var name: String
    private lateinit var content: FrameLayout
    private lateinit var status: TextView
    private var zoomView: ZoomImageView? = null
    private var imageWidth = 0
    private var imageHeight = 0
    private var fileSize = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        location = intent.readFileLocation() ?: run { finish(); return }
        name = intent.fileDisplayName() ?: location.displayPath.substringAfterLast('/').substringAfterLast("!/").ifBlank { "Imagem" }
        setContentView(buildUi())
        load()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized && ::location.isInitialized) load()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(UiPreferences.background(this@ImageViewerActivity))
        val bar = LinearLayout(this@ImageViewerActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3), 0, dp(4), 0)
            setBackgroundColor(UiPreferences.surface(this@ImageViewerActivity))
        }
        bar.addView(tool("←", "Voltar") { finish() })
        status = TextView(this@ImageViewerActivity).apply {
            text = name
            maxLines = 2
            textSize = 12.5f
            setTextColor(UiPreferences.textPrimary(this@ImageViewerActivity))
        }
        bar.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(tool("EDIT", "Editar imagem") { openEditor() })
        bar.addView(tool("FIT", "Ajustar à tela") { zoomView?.fitToScreen() })
        bar.addView(tool("↻", "Girar 90 graus") { zoomView?.rotateClockwise() })
        bar.addView(tool("⋮", "Mais") { showMore() })
        addView(bar, LinearLayout.LayoutParams(-1, dp(54)))
        content = FrameLayout(this@ImageViewerActivity).apply {
            setBackgroundColor(UiPreferences.background(this@ImageViewerActivity))
        }
        addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun tool(label: String, description: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = if (label.length > 2) 8.5f else 15f
        contentDescription = description
        setTextColor(UiPreferences.textPrimary(this@ImageViewerActivity))
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        minWidth = dp(40)
        minimumWidth = dp(40)
        setPadding(dp(3), 0, dp(3), 0)
        setOnClickListener { action() }
    }

    private fun load() {
        if (!::status.isInitialized) return
        status.text = "Abrindo $name…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val backend = graph.resolver.backendFor(location)
                    val node = backend.stat(location)
                    fileSize = node.size.coerceAtLeast(0)
                    val ext = name.substringAfterLast('.', "").lowercase()
                    when {
                        ext == "svg" -> Loaded.Svg(readLimited(MAX_SVG_BYTES))
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && ext in ANIMATED_EXTENSIONS && fileSize <= MAX_ANIMATED_BYTES -> decodeAnimatedOrStatic(ext)
                        else -> Loaded.Static(decodeSampledBitmap())
                    }
                }
            }.onSuccess(::render).onFailure { showError(it.message ?: "Falha ao abrir imagem") }
        }
    }

    private suspend fun readLimited(limit: Long): ByteArray {
        require(fileSize <= limit) { "Arquivo grande demais para este visualizador" }
        val backend = graph.resolver.backendFor(location)
        return backend.openInput(location).use { input ->
            val out = ByteArrayOutputStream(fileSize.coerceAtMost(256 * 1024).toInt())
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit) { "Arquivo excede o limite seguro" }
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }

    private suspend fun decodeAnimatedOrStatic(ext: String): Loaded {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return Loaded.Static(decodeSampledBitmap())
        val source = (location as? FileLocation.Direct)?.path?.let(::File)?.takeIf { it.isFile && it.canRead() }
            ?.let(ImageDecoder::createSource)
            ?: ImageDecoder.createSource(ByteBuffer.wrap(readLimited(MAX_ANIMATED_BYTES)))
        val drawable = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
            imageWidth = info.size.width
            imageHeight = info.size.height
            val sample = calculateSample(info.size.width, info.size.height, targetLongSide())
            if (sample > 1) decoder.setTargetSampleSize(sample)
        }
        return Loaded.Drawable(drawable, ext.uppercase())
    }

    private suspend fun decodeSampledBitmap(): Bitmap {
        val direct = (location as? FileLocation.Direct)?.path?.let(::File)?.takeIf { it.isFile && it.canRead() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (direct != null) BitmapFactory.decodeFile(direct.path, bounds)
        else graph.resolver.backendFor(location).openInput(location).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Formato de imagem não suportado pelo Android deste aparelho" }
        imageWidth = bounds.outWidth
        imageHeight = bounds.outHeight
        val sample = calculateSample(bounds.outWidth, bounds.outHeight, targetLongSide())
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return (if (direct != null) BitmapFactory.decodeFile(direct.path, options)
        else graph.resolver.backendFor(location).openInput(location).use { BitmapFactory.decodeStream(it, null, options) })
            ?: error("Não foi possível decodificar a imagem")
    }

    private fun calculateSample(width: Int, height: Int, target: Int): Int {
        var sample = 1
        while (max(width / sample, height / sample) > target && sample < 64) sample *= 2
        return sample
    }

    private fun targetLongSide(): Int {
        val display = resources.displayMetrics
        return (max(display.widthPixels, display.heightPixels) * 3).coerceIn(2048, 6144)
    }

    private fun render(loaded: Loaded) {
        content.removeAllViews()
        zoomView = null
        when (loaded) {
            is Loaded.Svg -> renderSvg(loaded.bytes)
            is Loaded.Static -> renderDrawable(android.graphics.drawable.BitmapDrawable(resources, loaded.bitmap), "${imageWidth}×${imageHeight}")
            is Loaded.Drawable -> renderDrawable(loaded.drawable, loaded.label)
        }
    }

    private fun renderDrawable(drawable: android.graphics.drawable.Drawable, label: String) {
        val view = ZoomImageView(this).apply {
            setBackgroundColor(UiPreferences.background(this@ImageViewerActivity))
            setImageDrawable(drawable)
        }
        zoomView = view
        content.addView(view, FrameLayout.LayoutParams(-1, -1))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable) drawable.start()
        status.text = "$name  •  $label"
    }

    @Suppress("SetJavaScriptEnabled")
    private fun renderSvg(bytes: ByteArray) {
        val web = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.domStorageEnabled = false
            settings.databaseEnabled = false
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                @Deprecated("Compatibility")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
            }
            setBackgroundColor(UiPreferences.background(this@ImageViewerActivity))
            loadDataWithBaseURL(null, bytes.toString(Charsets.UTF_8), "image/svg+xml", "UTF-8", null)
        }
        content.addView(web, FrameLayout.LayoutParams(-1, -1))
        status.text = "$name  •  SVG"
    }

    private fun openEditor() {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext == "svg" || ext == "gif") {
            showError("O editor raster atual trabalha com PNG, JPG, WEBP, BMP e formatos estáticos. SVG/GIF continuam disponíveis no visualizador.")
            return
        }
        startActivity(Intent(this, ImageEditorActivity::class.java).putFileLocation(location, name))
    }

    private fun showInfo() {
        val resolution = if (imageWidth > 0 && imageHeight > 0) "\nResolução: ${imageWidth} × ${imageHeight}" else ""
        AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage("Caminho: ${location.displayPath}\nTamanho: ${FileListAdapter.formatBytes(fileSize)}$resolution")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showMore() {
        AlertDialog.Builder(this).setTitle(name)
            .setItems(arrayOf("Editar imagem", "Ajustar à tela", "Resetar zoom", "Girar 90°", "Informações", "Compartilhar", "Abrir externamente")) { _, which ->
                when (which) {
                    0 -> openEditor()
                    1 -> zoomView?.fitToScreen()
                    2 -> zoomView?.resetZoom()
                    3 -> zoomView?.rotateClockwise()
                    4 -> showInfo()
                    5 -> share()
                    6 -> openExternally()
                }
            }.show()
    }

    private fun share() = externalFileAction(send = true)
    private fun openExternally() = externalFileAction(send = false)

    private fun externalFileAction(send: Boolean) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { materializeForExternal() } }
                .onSuccess { file ->
                    val uri = FileProvider.getUriForFile(this@ImageViewerActivity, "$packageName.files", file)
                    val mime = URLConnection.guessContentTypeFromName(name) ?: "image/*"
                    val intent = if (send) Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                    else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { startActivity(Intent.createChooser(intent, if (send) "Compartilhar" else "Abrir com")) }
                        .onFailure { showError("Nenhum aplicativo compatível") }
                }.onFailure { showError(it.message ?: "Falha ao preparar arquivo") }
        }
    }

    private suspend fun materializeForExternal(): File {
        val direct = (location as? FileLocation.Direct)?.path?.let(::File)
        if (direct != null && direct.isFile && direct.canRead()) return direct
        require(fileSize <= MAX_EXTERNAL_COPY_BYTES) { "Arquivo grande demais para cópia temporária" }
        val dir = File(cacheDir, "image-share").apply { mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9._() -]"), "_").take(160).ifBlank { "image.bin" }
        val target = File(dir, "${System.nanoTime()}-$safe")
        graph.resolver.backendFor(location).openInput(location).use { input ->
            target.outputStream().buffered(128 * 1024).use { output -> input.copyTo(output, 128 * 1024) }
        }
        return target
    }

    private fun showError(message: String) = runCatching {
        if (!isFinishing && !isDestroyed) AlertDialog.Builder(this)
            .setTitle("Visualizador de imagem")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private sealed interface Loaded {
        data class Svg(val bytes: ByteArray) : Loaded
        data class Static(val bitmap: Bitmap) : Loaded
        data class Drawable(val drawable: android.graphics.drawable.Drawable, val label: String) : Loaded
    }

    companion object {
        private val ANIMATED_EXTENSIONS = setOf("gif", "webp")
        private const val MAX_SVG_BYTES = 8L * 1024 * 1024
        private const val MAX_ANIMATED_BYTES = 32L * 1024 * 1024
        private const val MAX_EXTERNAL_COPY_BYTES = 512L * 1024 * 1024
    }
}
