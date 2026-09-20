package com.forgemanager.app.features.editor

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
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.RandomAccessFile

class HexViewerActivity : Activity() {
    private lateinit var file: File
    private lateinit var content: TextView
    private lateinit var offsetLabel: TextView
    private var offset = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        file = File(intent.getStringExtra("path") ?: run { finish(); return })
        setContentView(buildUi())
        render()
    }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val bar = LinearLayout(this@HexViewerActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.rgb(42,42,42)) }
        fun add(label: String, action: () -> Unit) { bar.addView(Button(this@HexViewerActivity).apply { text = label; setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { action() } }) }
        add("←") { finish() }
        offsetLabel = TextView(this@HexViewerActivity).apply { setTextColor(Color.WHITE) }
        bar.addView(offsetLabel, LinearLayout.LayoutParams(0, -2, 1f))
        add("Ir") { goToOffset() }
        add("Editar") { editByte() }
        add("‹") { offset = (offset - PAGE).coerceAtLeast(0); render() }
        add("›") { offset = (offset + PAGE).coerceAtMost(file.length().coerceAtLeast(1) - 1); render() }
        addView(bar, LinearLayout.LayoutParams(-1, dp(52)))
        content = TextView(this@HexViewerActivity).apply { typeface = Typeface.MONOSPACE; setTextSize(12f); setTextColor(Color.DKGRAY); setPadding(dp(8), dp(8), dp(8), dp(8)); setTextIsSelectable(true) }
        addView(android.widget.ScrollView(this@HexViewerActivity).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun render() {
        val bytes = ByteArray(PAGE)
        val count = runCatching { RandomAccessFile(file, "r").use { it.seek(offset); it.read(bytes) } }.getOrElse { showError(it.message ?: "Falha ao ler"); return }
        val text = StringBuilder()
        for (lineStart in 0 until count step 16) {
            text.append(String.format("%08X  ", offset + lineStart))
            for (i in 0 until 16) text.append(if (lineStart + i < count) String.format("%02X ", bytes[lineStart + i].toInt() and 0xff) else "   ")
            text.append(" ")
            for (i in 0 until 16) if (lineStart + i < count) {
                val c = bytes[lineStart + i].toInt() and 0xff
                text.append(if (c in 32..126) c.toChar() else '.')
            }
            text.append('\n')
        }
        content.text = text
        offsetLabel.text = "${file.name}  0x${offset.toString(16).uppercase()} / ${file.length()}"
    }

    private fun goToOffset() {
        val input = EditText(this).apply { hint = "Offset (decimal ou 0xHEX)"; inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("Ir para offset").setView(input).setPositiveButton("Ir") { _, _ ->
            val value = input.text.toString().trim().let { if (it.startsWith("0x", true)) it.drop(2).toLongOrNull(16) else it.toLongOrNull() }
            if (value != null && value in 0..file.length()) { offset = value; render() } else toast("Offset inválido")
        }.setNegativeButton("Cancelar", null).show()
    }

    private fun editByte() {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20),0,dp(20),0) }
        val at = EditText(this).apply { hint = "Offset"; setText(offset.toString()) }
        val value = EditText(this).apply { hint = "Byte hexadecimal (00–FF)" }
        container.addView(at); container.addView(value)
        AlertDialog.Builder(this).setTitle("Editar um byte").setView(container).setPositiveButton("Gravar") { _, _ ->
            val position = at.text.toString().toLongOrNull()
            val byte = value.text.toString().toIntOrNull(16)
            if (position == null || byte == null || byte !in 0..255 || position !in 0 until file.length()) { toast("Valor inválido"); return@setPositiveButton }
            runCatching { RandomAccessFile(file, "rw").use { it.seek(position); it.write(byte); it.fd.sync() } }
                .onSuccess { offset = (position / PAGE) * PAGE; render() }.onFailure { showError(it.message ?: "Falha ao gravar") }
        }.setNegativeButton("Cancelar", null).show()
    }

    private fun showError(message: String) = AlertDialog.Builder(this).setTitle("Hex").setMessage(message).setPositiveButton("OK", null).show()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object { private const val PAGE = 4096 }
}
