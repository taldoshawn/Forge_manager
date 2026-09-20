package com.forgemanager.app.features.viewer

import android.app.Activity
import android.app.AlertDialog
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
import java.io.ByteArrayOutputStream

class DiskImageActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }
    private lateinit var location: FileLocation
    private lateinit var displayName: String
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        location = intent.readFileLocation() ?: run { finish(); return }
        displayName = intent.fileDisplayName() ?: location.displayPath.substringAfterLast('/').ifBlank { "disk.img" }
        setContentView(buildUi())
        inspect()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)
        val bar = LinearLayout(this@DiskImageActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            setBackgroundColor(Color.rgb(7, 10, 14))
        }
        bar.addView(button("←") { finish() })
        bar.addView(TextView(this@DiskImageActivity).apply {
            text = displayName
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 2
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(button("HEX") {
            startActivity(Intent(this@DiskImageActivity, HexViewerActivity::class.java).putFileLocation(location, displayName))
        })
        bar.addView(button("IMG") {
            startActivity(Intent(this@DiskImageActivity, ImageViewerActivity::class.java).putFileLocation(location, displayName))
        })
        addView(bar, LinearLayout.LayoutParams(-1, dp(54)))
        output = TextView(this@DiskImageActivity).apply {
            setPadding(dp(16), dp(14), dp(16), dp(24))
            setTextColor(Color.rgb(220, 228, 238))
            textSize = 13f
            setTextIsSelectable(true)
        }
        addView(ScrollView(this@DiskImageActivity).apply {
            setBackgroundColor(Color.BLACK)
            addView(output)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        minWidth = dp(44)
        setOnClickListener { action() }
    }

    private fun inspect() {
        output.text = "Analisando imagem…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val backend = graph.resolver.backendFor(location)
                    val node = backend.stat(location)
                    val prefix = backend.openInput(location).use { input -> readAtMost(input, HEADER_BYTES) }
                    buildReport(node.size, prefix)
                }
            }.onSuccess { output.text = it }
                .onFailure { showError(it.message ?: "Não foi possível ler a imagem") }
        }
    }

    private fun buildReport(size: Long, data: ByteArray): String = buildString {
        appendLine("IMAGEM DE DISCO")
        appendLine(displayName)
        appendLine("Tamanho: ${FileListAdapter.formatBytes(size)} ($size bytes)")
        appendLine()

        val format = detectFormat(data)
        appendLine("Formato detectado: ${format.label}")
        format.details.forEach(::appendLine)
        appendLine()
        appendLine("Ferramentas")
        appendLine("• HEX abre a imagem no editor hexadecimal do Forge Manager.")
        appendLine("• IMG tenta decodificar o arquivo como uma imagem gráfica, útil para arquivos .img que não são imagens de disco.")
        appendLine("• A inspeção é somente leitura e não monta partições automaticamente.")
    }

    private data class Detection(val label: String, val details: List<String> = emptyList())

    private fun detectFormat(data: ByteArray): Detection {
        if (startsWith(data, "ANDROID!".toByteArray(Charsets.US_ASCII))) {
            val pageSize = u32(data, 36)
            return Detection("Android Boot Image", listOf("Page size/header field: $pageSize"))
        }
        if (startsWith(data, "AVB0".toByteArray(Charsets.US_ASCII))) {
            return Detection("Android Verified Boot (VBMeta)")
        }
        if (u32(data, 0) == 0xED26FF3AL) {
            val major = u16(data, 4)
            val minor = u16(data, 6)
            val blockSize = u32(data, 12)
            val totalBlocks = u32(data, 16)
            val chunks = u32(data, 20)
            return Detection("Android Sparse Image", listOf(
                "Sparse version: $major.$minor",
                "Block size: $blockSize",
                "Blocos: $totalBlocks",
                "Chunks: $chunks",
                "Tamanho expandido estimado: ${FileListAdapter.formatBytes(blockSize * totalBlocks)}"
            ))
        }
        if (u16(data, 1080) == 0xEF53) {
            val blocks = u32(data, 1028)
            val logBlock = u32(data, 1048).toInt().coerceIn(0, 6)
            val blockSize = 1024L shl logBlock
            val volume = ascii(data, 1144, 16)
            return Detection("EXT2/EXT3/EXT4", listOf(
                "Block size: $blockSize",
                "Blocos (campo low): $blocks",
                if (volume.isBlank()) "Volume: sem nome" else "Volume: $volume"
            ))
        }
        if (u32(data, 1024) == 0xE0F5E1E2L) return Detection("EROFS")
        if (u32(data, 1024) == 0xF2F52010L) return Detection("F2FS")
        if (startsWith(data, byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()))) return Detection("PNG com extensão .img")
        if (data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte()) return Detection("JPEG com extensão .img")
        if (data.size >= 12 && ascii(data, 0, 4) == "RIFF" && ascii(data, 8, 4) == "WEBP") return Detection("WebP com extensão .img")
        if (startsWith(data, "GIF8".toByteArray(Charsets.US_ASCII))) return Detection("GIF com extensão .img")
        return Detection("RAW/Desconhecido", listOf("O Forge Manager não reconheceu uma assinatura conhecida no cabeçalho."))
    }

    private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(limit)
        val buffer = ByteArray(16 * 1024)
        var remaining = limit
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            out.write(buffer, 0, count)
            remaining -= count
        }
        return out.toByteArray()
    }

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean = data.size >= prefix.size && prefix.indices.all { data[it] == prefix[it] }
    private fun u16(data: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > data.size) return -1
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }
    private fun u32(data: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > data.size) return -1
        return (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)
    }
    private fun ascii(data: ByteArray, offset: Int, count: Int): String {
        if (offset < 0 || offset >= data.size) return ""
        val end = minOf(data.size, offset + count)
        return data.copyOfRange(offset, end).toString(Charsets.ISO_8859_1).trimEnd('\u0000', ' ')
    }

    private fun showError(message: String) = AlertDialog.Builder(this)
        .setTitle("Imagem de disco")
        .setMessage(message)
        .setPositiveButton("OK", null)
        .show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object { private const val HEADER_BYTES = 4096 }
}
