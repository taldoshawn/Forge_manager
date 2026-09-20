package com.forgemanager.app.features.torrent

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
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
import java.nio.charset.StandardCharsets

class TorrentInspectorActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val location = intent.readFileLocation() ?: run { finish(); return }
        val name = intent.fileDisplayName() ?: "arquivo.torrent"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(TextView(this@TorrentInspectorActivity).apply {
                text = "Torrent Inspector  •  $name"
                setTextColor(Color.WHITE)
                textSize = 18f
                setPadding(dp(16), dp(16), dp(16), dp(10))
            })
        }
        output = TextView(this).apply {
            setTextColor(Color.rgb(220, 226, 235))
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(16), dp(8), dp(16), dp(24))
            text = "Lendo metadados…"
        }
        root.addView(ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { load(location) } }
                .onSuccess { output.text = it }
                .onFailure { showError(it.message ?: "Torrent inválido") }
        }
    }

    private fun load(location: FileLocation): String {
        val backend = graph.resolver.backendFor(location)
        val stat = backend.stat(location)
        require(stat.size in 1..MAX_BYTES) { "Torrent grande demais para inspeção" }
        val bytes = backend.openInput(location).use { input ->
            val out = ByteArrayOutputStream(stat.size.toInt().coerceAtMost(MAX_BYTES.toInt()))
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_BYTES) { "Torrent excede o limite de inspeção" }
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        val root = BencodeParser(bytes).parse() as? Map<*, *> ?: error("Estrutura bencode inválida")
        val info = root["info"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val torrentName = info.string("name") ?: info.string("name.utf-8") ?: "—"
        val pieceLength = info.long("piece length")
        val announce = root.string("announce")
        val createdBy = root.string("created by")
        val comment = root.string("comment")
        val files = info["files"] as? List<*>
        val totalSize = if (files != null) files.sumOf { (it as? Map<*, *>)?.long("length") ?: 0L }
            else info.long("length") ?: 0L
        return buildString {
            append("Nome: ").append(torrentName).append('\n')
            append("Tamanho total: ").append(formatBytes(totalSize)).append('\n')
            pieceLength?.let { append("Piece length: ").append(formatBytes(it)).append('\n') }
            announce?.let { append("Tracker: ").append(it).append('\n') }
            createdBy?.let { append("Criado por: ").append(it).append('\n') }
            comment?.let { append("Comentário: ").append(it).append('\n') }
            if (files != null) {
                append("\nArquivos (${files.size}):\n")
                files.take(500).forEach { entry ->
                    val map = entry as? Map<*, *> ?: return@forEach
                    val path = (map["path.utf-8"] ?: map["path"] as? List<*>)
                    val pathText = when (path) {
                        is List<*> -> path.joinToString("/") { it?.toString().orEmpty() }
                        else -> "?"
                    }
                    append("• ").append(pathText).append("  (").append(formatBytes(map.long("length") ?: 0)).append(")\n")
                }
                if (files.size > 500) append("… mais ").append(files.size - 500).append(" arquivos\n")
            }
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun showError(message: String) = AlertDialog.Builder(this)
        .setTitle("Torrent Inspector")
        .setMessage(message)
        .setPositiveButton("OK", null)
        .show()

    private fun Map<*, *>.string(key: String): String? = this[key]?.toString()
    private fun Map<*, *>.long(key: String): Long? = (this[key] as? Number)?.toLong()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun formatBytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var size = value.toDouble(); var index = -1
        do { size /= 1024; index++ } while (size >= 1024 && index < units.lastIndex)
        return "%.2f %s".format(java.util.Locale.US, size, units[index])
    }

    private class BencodeParser(private val data: ByteArray) {
        private var p = 0
        fun parse(): Any {
            val result = value(0)
            require(p == data.size) { "Dados extras após bencode" }
            return result
        }
        private fun value(depth: Int): Any {
            require(depth <= 64) { "Bencode profundo demais" }
            require(p < data.size) { "Fim inesperado" }
            return when (data[p].toInt().toChar()) {
                'i' -> integer()
                'l' -> list(depth + 1)
                'd' -> dict(depth + 1)
                in '0'..'9' -> string()
                else -> error("Token bencode inválido em $p")
            }
        }
        private fun integer(): Long {
            p++
            val start = p
            while (p < data.size && data[p].toInt().toChar() != 'e') p++
            require(p < data.size) { "Inteiro sem terminador" }
            val text = String(data, start, p - start, StandardCharsets.US_ASCII)
            p++
            return text.toLong()
        }
        private fun string(): String {
            val start = p
            while (p < data.size && data[p].toInt().toChar() != ':') p++
            require(p < data.size) { "String sem tamanho" }
            val length = String(data, start, p - start, StandardCharsets.US_ASCII).toInt()
            require(length in 0..MAX_STRING) { "String bencode grande demais" }
            p++
            require(p + length <= data.size) { "String truncada" }
            val text = String(data, p, length, StandardCharsets.UTF_8)
            p += length
            return text
        }
        private fun list(depth: Int): List<Any> {
            p++
            val out = ArrayList<Any>()
            while (p < data.size && data[p].toInt().toChar() != 'e') {
                require(out.size < 100_000) { "Lista bencode grande demais" }
                out += value(depth)
            }
            require(p < data.size) { "Lista sem terminador" }
            p++
            return out
        }
        private fun dict(depth: Int): Map<String, Any> {
            p++
            val out = LinkedHashMap<String, Any>()
            while (p < data.size && data[p].toInt().toChar() != 'e') {
                require(out.size < 100_000) { "Dicionário bencode grande demais" }
                val key = string()
                out[key] = value(depth)
            }
            require(p < data.size) { "Dicionário sem terminador" }
            p++
            return out
        }
        companion object { private const val MAX_STRING = 16 * 1024 * 1024 }
    }

    companion object { private const val MAX_BYTES = 16L * 1024 * 1024 }
}
