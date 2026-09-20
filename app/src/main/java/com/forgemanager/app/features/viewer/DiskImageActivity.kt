package com.forgemanager.app.features.viewer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.forgemanager.app.ForgeApplication
import com.forgemanager.app.core.file.FileLocation
import com.forgemanager.app.core.file.fileDisplayName
import com.forgemanager.app.core.file.putFileLocation
import com.forgemanager.app.core.file.readFileLocation
import com.forgemanager.app.features.editor.HexViewerActivity
import com.forgemanager.app.features.explorer.FileListAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DiskImageActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }
    private lateinit var location: FileLocation
    private lateinit var name: String
    private lateinit var output: TextView
    private var bitmapLike = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        location = intent.readFileLocation() ?: run { finish(); return }
        name = intent.fileDisplayName() ?: "arquivo.img"
        setContentView(buildUi())
        inspect()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)
        val bar = LinearLayout(this@DiskImageActivity).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.BLACK) }
        bar.addView(button("←") { finish() })
        bar.addView(TextView(this@DiskImageActivity).apply { text = name; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(button("HEX") { startActivity(Intent(this@DiskImageActivity, HexViewerActivity::class.java).putFileLocation(location, name)) })
        bar.addView(button("VIEW") { startActivity(Intent(this@DiskImageActivity, ImageViewerActivity::class.java).putFileLocation(location, name)) })
        addView(bar, LinearLayout.LayoutParams(-1, dp(54)))
        output = TextView(this@DiskImageActivity).apply {
            setTextColor(Color.LTGRAY)
            setPadding(dp(14), dp(12), dp(14), dp(24))
            setTextIsSelectable(true)
            textSize = 13f
        }
        addView(ScrollView(this@DiskImageActivity).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 10f
        setTextColor(Color.rgb(0, 190, 255))
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { action() }
    }

    private fun inspect() {
        output.text = "Lendo cabeçalho…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val backend = graph.resolver.backendFor(location)
                    val stat = backend.stat(location)
                    val header = ByteArray(0x9000)
                    val count = backend.openInput(location).use { input ->
                        var total = 0
                        while (total < header.size) {
                            val n = input.read(header, total, header.size - total)
                            if (n < 0) break
                            total += n
                        }
                        total
                    }
                    stat.size to header.copyOf(count)
                }
            }.onSuccess { (size, header) -> output.text = report(size, header) }
                .onFailure { output.text = "Falha ao abrir IMG: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    private fun report(size: Long, data: ByteArray): String = buildString {
        appendLine("IMAGEM / CONTAINER RAW")
        appendLine("Nome: $name")
        appendLine("Tamanho: ${FileListAdapter.formatBytes(size)} ($size bytes)")
        appendLine("Caminho: ${location.displayPath}")
        appendLine()

        val kind = detect(data)
        appendLine("Formato detectado: ${kind.label}")
        bitmapLike = kind.bitmap
        when (kind) {
            Format.ANDROID_SPARSE -> if (data.size >= 28) {
                val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                appendLine("Major: ${b.getShort(4).toInt() and 0xffff}")
                appendLine("Minor: ${b.getShort(6).toInt() and 0xffff}")
                appendLine("Block size: ${b.getInt(12)}")
                appendLine("Blocks: ${b.getInt(16)}")
                appendLine("Chunks: ${b.getInt(20)}")
            }
            Format.ANDROID_BOOT -> appendLine("Cabeçalho ANDROID! encontrado. Pode ser boot/recovery image.")
            Format.EXT4 -> appendLine("Superbloco ext4 identificado pelo magic 0xEF53.")
            Format.ISO9660 -> appendLine("Primary Volume Descriptor ISO9660 identificado.")
            Format.FAT -> appendLine("Assinatura de boot FAT/MBR 0x55AA encontrada.")
            Format.PNG, Format.JPEG, Format.WEBP -> appendLine("O arquivo usa extensão .img, mas o conteúdo é uma imagem visualizável. Use VIEW.")
            Format.UNKNOWN -> appendLine("Formato não reconhecido pelo cabeçalho. O editor hexadecimal continua disponível.")
        }
        appendLine()
        appendLine("Primeiros bytes:")
        append(data.take(64).chunked(16).joinToString("\n") { row -> row.joinToString(" ") { "%02X".format(it.toInt() and 0xff) } })
    }

    private fun detect(data: ByteArray): Format {
        fun ascii(offset: Int, value: String): Boolean = data.size >= offset + value.length &&
            value.indices.all { data[offset + it].toInt().toChar() == value[it] }
        if (data.size >= 4) {
            val magic = ByteBuffer.wrap(data, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (magic == 0xED26FF3A.toInt()) return Format.ANDROID_SPARSE
        }
        if (ascii(0, "ANDROID!")) return Format.ANDROID_BOOT
        if (data.size > 0x439 && (data[0x438].toInt() and 0xff) == 0x53 && (data[0x439].toInt() and 0xff) == 0xEF) return Format.EXT4
        if (data.size > 0x8005 && ascii(0x8001, "CD001")) return Format.ISO9660
        if (data.size >= 512 && (data[510].toInt() and 0xff) == 0x55 && (data[511].toInt() and 0xff) == 0xAA) return Format.FAT
        if (data.size >= 8 && data.take(8).map { it.toInt() and 0xff } == listOf(0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A)) return Format.PNG
        if (data.size >= 3 && (data[0].toInt() and 0xff) == 0xFF && (data[1].toInt() and 0xff) == 0xD8 && (data[2].toInt() and 0xff) == 0xFF) return Format.JPEG
        if (ascii(0, "RIFF") && ascii(8, "WEBP")) return Format.WEBP
        return Format.UNKNOWN
    }

    private enum class Format(val label: String, val bitmap: Boolean = false) {
        ANDROID_SPARSE("Android Sparse Image"), ANDROID_BOOT("Android Boot Image"), EXT4("ext4 filesystem"),
        ISO9660("ISO9660"), FAT("FAT/MBR-like image"), PNG("PNG", true), JPEG("JPEG", true), WEBP("WebP", true), UNKNOWN("desconhecido")
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
