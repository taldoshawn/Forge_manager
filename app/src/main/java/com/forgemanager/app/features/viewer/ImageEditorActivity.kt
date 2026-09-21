package com.forgemanager.app.features.viewer

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
import java.io.File
import java.io.FileOutputStream

class ImageEditorActivity : ForgeActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }
    private lateinit var location: FileLocation
    private lateinit var name: String
    private lateinit var editor: ImageEditView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        location = intent.readFileLocation() ?: run { finish(); return }
        name = intent.fileDisplayName() ?: location.displayPath.substringAfterLast('/').substringAfterLast("!/").ifBlank { "imagem.png" }
        setContentView(buildUi())
        load()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(UiPreferences.background(this@ImageEditorActivity))

        val top = LinearLayout(this@ImageEditorActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(UiPreferences.surface(this@ImageEditorActivity))
            setPadding(dp(2), 0, dp(2), 0)
        }
        top.addView(tool("←", "Voltar") { finish() })
        status = TextView(this@ImageEditorActivity).apply {
            text = "Editor • $name"
            maxLines = 1
            textSize = 12.5f
            setTextColor(UiPreferences.textPrimary(this@ImageEditorActivity))
            setPadding(dp(4), 0, dp(4), 0)
        }
        top.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(tool("↶", "Desfazer") { if (!editor.undo()) toast("Nada para desfazer") })
        top.addView(tool("✓", "Salvar") { save(overwrite = true) })
        addView(top, LinearLayout.LayoutParams(-1, dp(54)))

        val tools = LinearLayout(this@ImageEditorActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(UiPreferences.elevatedSurface(this@ImageEditorActivity))
        }
        tools.addView(tool("REC", "Recortar") { showCropMenu() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        tools.addView(tool("CANETA", "Caneta") { editor.setTool(ImageEditView.Tool.PEN); status.text = "Caneta • $name" }, LinearLayout.LayoutParams(0, dp(44), 1f))
        tools.addView(tool("COR", "Cor da caneta") { chooseColor() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        tools.addView(tool("BORR", "Borracha") { editor.setTool(ImageEditView.Tool.ERASER); status.text = "Borracha • $name" }, LinearLayout.LayoutParams(0, dp(44), 1f))
        tools.addView(tool("↻", "Girar") { editor.rotateClockwise(); status.text = "Girado • $name" }, LinearLayout.LayoutParams(0, dp(44), 1f))
        tools.addView(tool("⋮", "Mais") { showMore() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        addView(tools)

        editor = ImageEditView(this@ImageEditorActivity).apply {
            setBackgroundColor(UiPreferences.background(this@ImageEditorActivity))
        }
        addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun tool(label: String, description: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = if (label.length > 2) 8.5f else 15f
        contentDescription = description
        setTextColor(UiPreferences.textPrimary(this@ImageEditorActivity))
        setBackgroundColor(Color.TRANSPARENT)
        minWidth = dp(38)
        minimumWidth = dp(38)
        setPadding(dp(3), 0, dp(3), 0)
        setOnClickListener { action() }
    }

    private fun load() {
        status.text = "Carregando $name…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { decodeEditableBitmap() } }
                .onSuccess {
                    editor.setBitmap(it)
                    status.text = "$name • ${it.width}×${it.height}"
                }
                .onFailure { showError(it.message ?: "Falha ao abrir imagem") }
        }
    }

    private suspend fun decodeEditableBitmap(): Bitmap {
        val backend = graph.resolver.backendFor(location)
        val node = backend.stat(location)
        require(node.size in 1..MAX_FILE_BYTES) { "Imagem vazia ou grande demais para edição" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        backend.openInput(location).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Formato não suportado para edição" }
        val pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
        require(pixels <= MAX_PIXELS) { "Imagem muito grande para editar com segurança (${bounds.outWidth}×${bounds.outHeight})" }
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return backend.openInput(location).use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Não foi possível decodificar a imagem")
    }

    private fun showCropMenu() {
        val options = arrayOf("Quadrado 1:1", "Paisagem 4:3", "Widescreen 16:9", "Retrato 3:4", "Remover borda transparente")
        AlertDialog.Builder(this).setTitle("Recortar").setItems(options) { _, which ->
            when (which) {
                0 -> editor.cropCenter(1, 1)
                1 -> editor.cropCenter(4, 3)
                2 -> editor.cropCenter(16, 9)
                3 -> editor.cropCenter(3, 4)
                4 -> editor.trimTransparentBorder()
            }
            status.text = "Recorte aplicado • $name"
        }.show()
    }

    private fun chooseColor() {
        val labels = arrayOf("Vermelho", "Azul", "Ciano", "Verde", "Amarelo", "Branco", "Preto")
        val values = intArrayOf(
            Color.rgb(235, 64, 75), Color.rgb(40, 120, 245), Color.rgb(0, 190, 220),
            Color.rgb(35, 190, 105), Color.rgb(245, 195, 40), Color.WHITE, Color.BLACK
        )
        AlertDialog.Builder(this).setTitle("Cor da caneta").setItems(labels) { _, which ->
            editor.setBrushColor(values[which])
            editor.setTool(ImageEditView.Tool.PEN)
            status.text = "Caneta ${labels[which].lowercase()} • $name"
        }.show()
    }

    private fun showMore() {
        AlertDialog.Builder(this).setTitle("Editor de imagem")
            .setItems(arrayOf("Caneta fina", "Caneta média", "Caneta grossa", "Modo sem desenho", "Salvar original", "Salvar uma cópia")) { _, which ->
                when (which) {
                    0 -> { editor.setBrushWidthDp(3f); editor.setTool(ImageEditView.Tool.PEN) }
                    1 -> { editor.setBrushWidthDp(8f); editor.setTool(ImageEditView.Tool.PEN) }
                    2 -> { editor.setBrushWidthDp(18f); editor.setTool(ImageEditView.Tool.PEN) }
                    3 -> editor.setTool(ImageEditView.Tool.PAN)
                    4 -> save(overwrite = true)
                    5 -> save(overwrite = false)
                }
            }.show()
    }

    private fun save(overwrite: Boolean) {
        val bitmap = editor.bitmapCopy() ?: return
        status.text = "Salvando…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (overwrite) writeToLocation(bitmap) else writeCopy(bitmap)
                }
            }.onSuccess { saved ->
                status.text = "Salvo • ${saved ?: name}"
                toast(if (overwrite) "Imagem salva" else "Cópia criada: $saved")
            }.onFailure { showError(it.message ?: "Falha ao salvar") }
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private suspend fun writeToLocation(bitmap: Bitmap) {
        val backend = graph.resolver.backendFor(location, write = true)
        backend.openOutput(location, truncate = true).use { output ->
            require(bitmap.compress(formatFor(name), 95, output)) { "Falha ao codificar imagem" }
            output.flush()
        }
    }

    private fun writeCopy(bitmap: Bitmap): String {
        val direct = (location as? FileLocation.Direct)?.path?.let(::File)
            ?: error("Salvar cópia está disponível para arquivos locais; use Salvar original neste backend")
        val parent = direct.parentFile ?: error("Diretório inválido")
        val ext = direct.extension.ifBlank { "png" }
        val base = direct.nameWithoutExtension.ifBlank { "imagem" }
        var candidate = File(parent, "${base}_editado.$ext")
        var index = 2
        while (candidate.exists()) {
            candidate = File(parent, "${base}_editado_$index.$ext")
            index++
        }
        FileOutputStream(candidate).use { output ->
            require(bitmap.compress(formatFor(candidate.name), 95, output)) { "Falha ao codificar imagem" }
            output.fd.sync()
        }
        return candidate.name
    }

    @Suppress("DEPRECATION")
    private fun formatFor(fileName: String): Bitmap.CompressFormat {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
            "webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.PNG
        }
    }

    private fun showError(message: String) = runCatching {
        if (!isFinishing && !isDestroyed) AlertDialog.Builder(this)
            .setTitle("Editor de imagem")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_FILE_BYTES = 96L * 1024 * 1024
        private const val MAX_PIXELS = 24_000_000L
    }
}
