package com.forgemanager.app.features.editor

import com.forgemanager.app.core.ui.ForgeActivity

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
import java.io.File
import java.io.RandomAccessFile

class HexViewerActivity : ForgeActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }
    private lateinit var location: FileLocation
    private lateinit var displayName: String
    private lateinit var content: TextView
    private lateinit var offsetLabel: TextView
    private var offset = 0L
    private var size = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        location = intent.readFileLocation() ?: run { finish(); return }
        displayName = intent.fileDisplayName() ?: location.displayPath.substringAfterLast('/').ifBlank { "arquivo" }
        setContentView(buildUi())
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { graph.resolver.backendFor(location).stat(location).size } }
                .onSuccess { size = it.coerceAtLeast(0); render() }
                .onFailure { showError(it.message ?: "Falha ao ler arquivo") }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(15, 18, 24))
        val bar = LinearLayout(this@HexViewerActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(31, 35, 43))
        }
        fun add(label: String, action: () -> Unit) {
            bar.addView(Button(this@HexViewerActivity).apply {
                text = label
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.TRANSPARENT)
                minWidth = dp(44)
                setOnClickListener { action() }
            })
        }
        add("←") { finish() }
        offsetLabel = TextView(this@HexViewerActivity).apply { setTextColor(Color.WHITE); maxLines = 2 }
        bar.addView(offsetLabel, LinearLayout.LayoutParams(0, -2, 1f))
        add("Ir") { goToOffset() }
        add("Editar") { editByte() }
        add("‹") { offset = (offset - PAGE).coerceAtLeast(0); render() }
        add("›") { offset = (offset + PAGE).coerceAtMost((size - 1).coerceAtLeast(0)); render() }
        addView(bar, LinearLayout.LayoutParams(-1, dp(54)))
        content = TextView(this@HexViewerActivity).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(12f)
            setTextColor(Color.rgb(203, 213, 225))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setTextIsSelectable(true)
        }
        addView(ScrollView(this@HexViewerActivity).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun render() {
        offsetLabel.text = "$displayName  •  lendo 0x${offset.toString(16).uppercase()}"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { readPage() } }
                .onSuccess { bytes ->
                    val text = StringBuilder()
                    for (lineStart in bytes.indices step 16) {
                        text.append(String.format("%08X  ", offset + lineStart))
                        for (i in 0 until 16) {
                            text.append(if (lineStart + i < bytes.size) String.format("%02X ", bytes[lineStart + i].toInt() and 0xff) else "   ")
                        }
                        text.append(" ")
                        for (i in 0 until 16) if (lineStart + i < bytes.size) {
                            val c = bytes[lineStart + i].toInt() and 0xff
                            text.append(if (c in 32..126) c.toChar() else '.')
                        }
                        text.append('\n')
                    }
                    content.text = text
                    offsetLabel.text = "$displayName  •  0x${offset.toString(16).uppercase()} / $size"
                }.onFailure { showError(it.message ?: "Falha ao ler") }
        }
    }

    private suspend fun readPage(): ByteArray {
        val direct = (location as? FileLocation.Direct)?.path?.let(::File)
        if (direct != null && direct.canRead()) {
            val data = ByteArray(PAGE)
            val count = RandomAccessFile(direct, "r").use { file ->
                file.seek(offset.coerceAtMost(file.length()))
                file.read(data)
            }
            return if (count <= 0) ByteArray(0) else data.copyOf(count)
        }
        val backend = graph.resolver.backendFor(location)
        return backend.openInput(location).use { input ->
            var remaining = offset
            val scratch = ByteArray(32 * 1024)
            while (remaining > 0) {
                val read = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
                if (read < 0) return@use ByteArray(0)
                remaining -= read
            }
            val data = ByteArray(PAGE)
            val count = input.read(data)
            if (count <= 0) ByteArray(0) else data.copyOf(count)
        }
    }

    private fun goToOffset() {
        val input = EditText(this).apply { hint = "Offset (decimal ou 0xHEX)"; inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("Ir para offset").setView(input).setPositiveButton("Ir") { _, _ ->
            val value = input.text.toString().trim().let { if (it.startsWith("0x", true)) it.drop(2).toLongOrNull(16) else it.toLongOrNull() }
            if (value != null && value in 0..size) { offset = value; render() } else toast("Offset inválido")
        }.setNegativeButton("Cancelar", null).show()
    }

    private fun editByte() {
        if (size <= 0) return
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
        val at = EditText(this).apply { hint = "Offset"; setText(offset.toString()) }
        val value = EditText(this).apply { hint = "Byte hexadecimal (00–FF)" }
        container.addView(at)
        container.addView(value)
        AlertDialog.Builder(this).setTitle("Editar um byte").setView(container).setPositiveButton("Gravar") { _, _ ->
            val position = at.text.toString().toLongOrNull()
            val byte = value.text.toString().toIntOrNull(16)
            if (position == null || byte == null || byte !in 0..255 || position !in 0 until size) {
                toast("Valor inválido")
                return@setPositiveButton
            }
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { writeByte(position, byte) } }
                    .onSuccess { offset = (position / PAGE) * PAGE; render(); toast("Byte gravado") }
                    .onFailure { showError(it.message ?: "Falha ao gravar") }
            }
        }.setNegativeButton("Cancelar", null).show()
    }

    private suspend fun writeByte(position: Long, value: Int) {
        val direct = (location as? FileLocation.Direct)?.path?.let(::File)
        if (direct != null && direct.canWrite()) {
            RandomAccessFile(direct, "rw").use { file -> file.seek(position); file.write(value); file.fd.sync() }
            return
        }
        if (size > MAX_REWRITE_BYTES) error("Edição hexadecimal de arquivo não local é limitada a 64 MB")
        val backend = graph.resolver.backendFor(location)
        val bytes = backend.openInput(location).use { input ->
            val out = ByteArrayOutputStream(size.toInt())
            input.copyTo(out, 64 * 1024)
            out.toByteArray()
        }
        bytes[position.toInt()] = value.toByte()
        graph.resolver.backendFor(location, write = true).openOutput(location, truncate = true).use { output ->
            output.write(bytes)
            output.flush()
        }
    }

    private fun showError(message: String) = AlertDialog.Builder(this).setTitle("Hex").setMessage(message).setPositiveButton("OK", null).show()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PAGE = 4096
        private const val MAX_REWRITE_BYTES = 64L * 1024 * 1024
    }
}
