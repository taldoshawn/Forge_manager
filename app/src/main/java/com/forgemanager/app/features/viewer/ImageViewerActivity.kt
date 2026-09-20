package com.forgemanager.app.features.viewer

import android.app.Activity
import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class ImageViewerActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }
    private lateinit var location: FileLocation
    private lateinit var name: String
    private lateinit var content: FrameLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        location = intent.readFileLocation() ?: run { finish(); return }
        name = intent.fileDisplayName() ?: location.displayPath.substringAfterLast('/').ifBlank { "Imagem" }
        setContentView(buildUi())
        load()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)
        val bar = LinearLayout(this@ImageViewerActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(8), 0)
            setBackgroundColor(Color.rgb(5, 5, 5))
        }
        bar.addView(Button(this@ImageViewerActivity).apply {
            text = "←"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        })
        status = TextView(this@ImageViewerActivity).apply {
            text = name
            maxLines = 2
            setTextColor(Color.WHITE)
        }
        bar.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        addView(bar, LinearLayout.LayoutParams(-1, dp(54)))
        content = FrameLayout(this@ImageViewerActivity).apply { setBackgroundColor(Color.BLACK) }
        addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun load() {
        status.text = "Abrindo $name…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val backend = graph.resolver.backendFor(location)
                    val node = backend.stat(location)
                    if (node.size > MAX_IMAGE_BYTES) error("Imagem muito grande para visualização interna")
                    backend.openInput(location).use { input ->
                        val out = ByteArrayOutputStream(if (node.size in 1..Int.MAX_VALUE) node.size.toInt() else 256 * 1024)
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_IMAGE_BYTES) error("Imagem excede o limite de visualização")
                            out.write(buffer, 0, read)
                        }
                        out.toByteArray()
                    }
                }
            }.onSuccess(::render).onFailure { showError(it.message ?: "Falha ao abrir imagem") }
        }
    }

    private fun render(bytes: ByteArray) {
        content.removeAllViews()
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext == "svg") {
            val web = WebView(this).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                setBackgroundColor(Color.TRANSPARENT)
                loadDataWithBaseURL(null, bytes.toString(Charsets.UTF_8), "image/svg+xml", "UTF-8", null)
            }
            content.addView(web, FrameLayout.LayoutParams(-1, -1))
            status.text = "$name  •  SVG"
            return
        }

        val view = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        val drawable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) }.getOrNull()
        } else null
        if (drawable != null) {
            view.setImageDrawable(drawable)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable) drawable.start()
        } else {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: run { showError("Formato de imagem não suportado pelo Android deste aparelho"); return }
            view.setImageBitmap(bitmap)
        }
        content.addView(view, FrameLayout.LayoutParams(-1, -1))
        status.text = "$name  •  ${bytes.size / 1024} KB"
    }

    private fun showError(message: String) = AlertDialog.Builder(this)
        .setTitle("Visualizador de imagem")
        .setMessage(message)
        .setPositiveButton("Fechar") { _, _ -> finish() }
        .show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object { private const val MAX_IMAGE_BYTES = 64L * 1024 * 1024 }
}
