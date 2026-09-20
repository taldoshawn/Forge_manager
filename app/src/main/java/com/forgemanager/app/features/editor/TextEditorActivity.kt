package com.forgemanager.app.features.editor

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class TextEditorActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var editor: LineNumberEditText
    private lateinit var status: TextView
    private lateinit var file: File
    private var charset: Charset = Charsets.UTF_8
    private var bom = ByteArray(0)
    private var original = ""
    private var loading = false
    private var readOnly = false
    private val undo = ArrayDeque<String>()
    private val redo = ArrayDeque<String>()
    private var previous = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        file = File(intent.getStringExtra("path") ?: run { finish(); return })
        setContentView(buildUi())
        load()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun onBackPressed() {
        if (!loading && editor.text.toString() != original && !readOnly) {
            AlertDialog.Builder(this).setTitle("Alterações não salvas")
                .setPositiveButton("Salvar") { _, _ -> save { finish() } }
                .setNegativeButton("Descartar") { _, _ -> finish() }
                .setNeutralButton("Cancelar", null).show()
        } else super.onBackPressed()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(4, 4, 4, 4); setBackgroundColor(Color.rgb(42,42,42)) }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label; setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); minWidth = 48; setOnClickListener { action() }
        }
        bar.addView(button("←") { onBackPressed() })
        status = TextView(this).apply { setTextColor(Color.WHITE); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
        bar.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(button("↶") { performUndo() })
        bar.addView(button("↷") { performRedo() })
        bar.addView(button("⌕") { showSearch() })
        bar.addView(button("✓") { save() })
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(52)))
        editor = LineNumberEditText(this)
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!loading && !readOnly && previous != s.toString()) {
                    if (previous.length <= HISTORY_TEXT_LIMIT) {
                        undo.addLast(previous)
                        while (undo.size > 40) undo.removeFirst()
                    }
                    previous = s.toString()
                    redo.clear()
                    updateStatus()
                }
            }
        })
        root.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun load() {
        status.text = "Abrindo ${file.name}…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val length = file.length()
                readOnly = length > MAX_EDIT_BYTES
                val limit = if (readOnly) PREVIEW_BYTES else length.toInt()
                val data = file.inputStream().use { input ->
                    val output = java.io.ByteArrayOutputStream(limit.coerceAtMost(256 * 1024))
                    val buffer = ByteArray(64 * 1024)
                    var remaining = limit
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        remaining -= count
                    }
                    output.toByteArray()
                }
                detectEncoding(data)
            }}.onSuccess { text ->
                loading = true
                original = text
                previous = text
                editor.setText(text)
                editor.setSelection(0)
                editor.isEnabled = !readOnly
                loading = false
                updateStatus()
                if (readOnly) Toast.makeText(this@TextEditorActivity, "Arquivo grande: prévia somente leitura", Toast.LENGTH_LONG).show()
            }.onFailure { showError(it.message ?: "Falha ao abrir") }
        }
    }

    private fun detectEncoding(data: ByteArray): String {
        return when {
            data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte() -> {
                charset = Charsets.UTF_8; bom = data.copyOfRange(0, 3); data.copyOfRange(3, data.size).toString(charset)
            }
            data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte() -> {
                charset = Charsets.UTF_16LE; bom = data.copyOfRange(0, 2); data.copyOfRange(2, data.size).toString(charset)
            }
            data.size >= 2 && data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte() -> {
                charset = Charsets.UTF_16BE; bom = data.copyOfRange(0, 2); data.copyOfRange(2, data.size).toString(charset)
            }
            else -> { charset = Charsets.UTF_8; bom = ByteArray(0); data.toString(charset) }
        }
    }

    private fun save(after: (() -> Unit)? = null) {
        if (readOnly) { showError("A prévia de arquivo grande não pode ser salva"); return }
        val content = editor.text.toString()
        status.text = "Salvando…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val temp = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
                try {
                    FileOutputStream(temp).use { output -> output.write(bom); output.write(content.toByteArray(charset)); output.fd.sync() }
                    try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                    catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                } finally { temp.delete() }
            }}.onSuccess { original = content; updateStatus(); after?.invoke() }.onFailure { showError(it.message ?: "Falha ao salvar") }
        }
    }

    private fun showSearch() {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
        val find = EditText(this).apply { hint = "Buscar" }
        val replace = EditText(this).apply { hint = "Substituir por" }
        container.addView(find); container.addView(replace)
        AlertDialog.Builder(this).setTitle("Buscar e substituir").setView(container)
            .setPositiveButton("Próximo") { _, _ -> findNext(find.text.toString()) }
            .setNeutralButton("Substituir todos") { _, _ -> replaceAll(find.text.toString(), replace.text.toString()) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun findNext(query: String) {
        if (query.isEmpty()) return
        val start = editor.selectionEnd.coerceAtLeast(0)
        val index = editor.text.indexOf(query, start, ignoreCase = true).let { if (it < 0) editor.text.indexOf(query, 0, true) else it }
        if (index >= 0) { editor.requestFocus(); editor.setSelection(index, index + query.length) } else Toast.makeText(this, "Não encontrado", Toast.LENGTH_SHORT).show()
    }

    private fun replaceAll(pattern: String, replacement: String) {
        if (pattern.isEmpty() || readOnly) return
        val source = editor.text.toString()
        val result = if (pattern.startsWith('/')) runCatching { Regex(pattern.drop(1)).replace(source, replacement) }.getOrElse { showError("Regex inválida"); return }
        else source.replace(pattern, replacement, ignoreCase = false)
        editor.setText(result)
    }

    private fun performUndo() {
        val value = undo.removeLastOrNull() ?: return
        redo.addLast(editor.text.toString())
        loading = true; editor.setText(value); editor.setSelection(value.length); previous = value; loading = false; updateStatus()
    }

    private fun performRedo() {
        val value = redo.removeLastOrNull() ?: return
        undo.addLast(editor.text.toString())
        loading = true; editor.setText(value); editor.setSelection(value.length); previous = value; loading = false; updateStatus()
    }

    private fun updateStatus() { status.text = "${file.name}  ${charset.name()}${if (!readOnly && editor.text.toString() != original) "  •" else ""}" }
    private fun showError(message: String) = AlertDialog.Builder(this).setTitle("Editor").setMessage(message).setPositiveButton("OK", null).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_EDIT_BYTES = 8 * 1024 * 1024L
        private const val PREVIEW_BYTES = 2 * 1024 * 1024
        private const val HISTORY_TEXT_LIMIT = 2 * 1024 * 1024
    }
}

private fun <T> ArrayDeque<T>.removeLastOrNull(): T? = if (isEmpty()) null else removeLast()
