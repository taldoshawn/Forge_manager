package com.forgemanager.app.features.editor

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.forgemanager.app.ForgeApplication
import com.forgemanager.app.core.file.FileLocation
import com.forgemanager.app.core.file.fileDisplayName
import com.forgemanager.app.core.file.putFileLocation
import com.forgemanager.app.core.file.readFileLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

class TextEditorActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var editor: LineNumberEditText
    private lateinit var status: TextView
    private lateinit var location: FileLocation
    private lateinit var displayName: String
    private var charset: Charset = Charsets.UTF_8
    private var bom = ByteArray(0)
    private var original = ""
    private var loading = false
    private var readOnly = false
    private val undo = ArrayDeque<String>()
    private val redo = ArrayDeque<String>()
    private var previous = ""

    private val highlightTask = Runnable {
        if (::editor.isInitialized && !loading) {
            val start = editor.selectionStart
            val end = editor.selectionEnd
            SyntaxHighlighter.apply(editor.text, displayName)
            if (start >= 0 && end >= 0 && start <= editor.length() && end <= editor.length()) {
                runCatching { editor.setSelection(start, end) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        location = intent.readFileLocation() ?: run { finish(); return }
        displayName = intent.fileDisplayName()
            ?: location.displayPath.substringAfterLast('/').substringAfterLast("!/").ifBlank { "arquivo" }
        setContentView(buildUi())
        load()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (!loading && ::editor.isInitialized && editor.text.toString() != original && !readOnly) {
            AlertDialog.Builder(this).setTitle("Alterações não salvas")
                .setMessage("Salvar as alterações em $displayName?")
                .setPositiveButton("Salvar") { _, _ -> save { finish() } }
                .setNegativeButton("Descartar") { _, _ -> finish() }
                .setNeutralButton("Cancelar", null).show()
        } else super.onBackPressed()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(15, 18, 24))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
            setBackgroundColor(Color.rgb(31, 35, 43))
        }
        fun button(label: String, description: String, action: () -> Unit) = Button(this).apply {
            text = label
            contentDescription = description
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            minWidth = dp(44)
            setOnClickListener { action() }
        }
        bar.addView(button("←", "Voltar") { onBackPressed() })
        status = TextView(this).apply {
            setTextColor(Color.rgb(220, 224, 231))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(dp(6), 0, dp(4), 0)
        }
        bar.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(button("↶", "Desfazer") { performUndo() })
        bar.addView(button("↷", "Refazer") { performRedo() })
        bar.addView(button("⌕", "Buscar e substituir") { showSearch() })
        if (displayName.substringAfterLast('.', "").lowercase() in setOf("html", "htm", "xhtml", "svg")) {
            bar.addView(button("▶", "Visualizar página") { previewHtml() })
        }
        bar.addView(button("✓", "Salvar") { save() })
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(54)))

        editor = LineNumberEditText(this).apply {
            setTextColor(Color.rgb(226, 232, 240))
            setHintTextColor(Color.rgb(100, 116, 139))
            setBackgroundColor(Color.rgb(15, 18, 24))
            setSelectAllOnFocus(false)
        }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!loading && !readOnly && previous != s.toString()) {
                    if (previous.length <= HISTORY_TEXT_LIMIT) {
                        undo.addLast(previous)
                        while (undo.size > 50) undo.removeFirst()
                    }
                    previous = s.toString()
                    redo.clear()
                    updateStatus()
                }
                scheduleHighlight()
            }
        })
        root.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun load() {
        status.text = "Abrindo $displayName…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val backend = graph.resolver.backendFor(location)
                    val size = backend.stat(location).size.coerceAtLeast(0)
                    readOnly = size > MAX_EDIT_BYTES
                    val limit = if (readOnly) PREVIEW_BYTES else MAX_EDIT_BYTES.toInt()
                    val data = backend.openInput(location).use { input -> readAtMost(input, limit) }
                    detectEncoding(data)
                }
            }.onSuccess { text ->
                loading = true
                original = text
                previous = text
                editor.setText(text)
                editor.setSelection(0)
                if (readOnly) {
                    editor.keyListener = null
                    editor.setTextIsSelectable(true)
                }
                loading = false
                SyntaxHighlighter.apply(editor.text, displayName)
                updateStatus()
                if (readOnly) Toast.makeText(this@TextEditorActivity, "Arquivo grande: prévia somente leitura", Toast.LENGTH_LONG).show()
            }.onFailure { showError(it.message ?: "Falha ao abrir") }
        }
    }

    private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 256 * 1024))
        val buffer = ByteArray(64 * 1024)
        var remaining = limit
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            output.write(buffer, 0, count)
            remaining -= count
        }
        return output.toByteArray()
    }

    private fun detectEncoding(data: ByteArray): String = when {
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

    private fun save(after: (() -> Unit)? = null) {
        if (readOnly) { showError("A prévia de arquivo grande não pode ser salva"); return }
        val content = editor.text.toString()
        status.text = "Salvando $displayName…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val backend = graph.resolver.backendFor(location, write = true)
                    backend.openOutput(location, truncate = true).use { output ->
                        output.write(bom)
                        output.write(content.toByteArray(charset))
                        output.flush()
                    }
                }
            }.onSuccess {
                original = content
                previous = content
                updateStatus()
                Toast.makeText(this@TextEditorActivity, "Salvo", Toast.LENGTH_SHORT).show()
                after?.invoke()
            }.onFailure { showError(it.message ?: "Falha ao salvar") }
        }
    }

    private fun previewHtml() {
        startActivity(Intent(this, HtmlPreviewActivity::class.java).putFileLocation(location, displayName))
    }

    private fun showSearch() {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
        val find = EditText(this).apply { hint = "Buscar" }
        val replace = EditText(this).apply { hint = "Substituir por" }
        container.addView(find)
        container.addView(replace)
        AlertDialog.Builder(this).setTitle("Buscar e substituir").setView(container)
            .setPositiveButton("Próximo") { _, _ -> findNext(find.text.toString()) }
            .setNeutralButton("Substituir todos") { _, _ -> replaceAll(find.text.toString(), replace.text.toString()) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun findNext(query: String) {
        if (query.isEmpty()) return
        val start = editor.selectionEnd.coerceAtLeast(0)
        val index = editor.text.indexOf(query, start, ignoreCase = true).let { if (it < 0) editor.text.indexOf(query, 0, true) else it }
        if (index >= 0) {
            editor.requestFocus()
            editor.setSelection(index, index + query.length)
        } else Toast.makeText(this, "Não encontrado", Toast.LENGTH_SHORT).show()
    }

    private fun replaceAll(pattern: String, replacement: String) {
        if (pattern.isEmpty() || readOnly) return
        val source = editor.text.toString()
        val result = if (pattern.startsWith('/')) {
            runCatching { Regex(pattern.drop(1)).replace(source, replacement) }.getOrElse { showError("Regex inválida"); return }
        } else source.replace(pattern, replacement, ignoreCase = false)
        editor.setText(result)
    }

    private fun performUndo() {
        val value = undo.removeLastOrNull() ?: return
        redo.addLast(editor.text.toString())
        loading = true
        editor.setText(value)
        editor.setSelection(value.length)
        previous = value
        loading = false
        SyntaxHighlighter.apply(editor.text, displayName)
        updateStatus()
    }

    private fun performRedo() {
        val value = redo.removeLastOrNull() ?: return
        undo.addLast(editor.text.toString())
        loading = true
        editor.setText(value)
        editor.setSelection(value.length)
        previous = value
        loading = false
        SyntaxHighlighter.apply(editor.text, displayName)
        updateStatus()
    }

    private fun scheduleHighlight() {
        handler.removeCallbacks(highlightTask)
        handler.postDelayed(highlightTask, 180)
    }

    private fun updateStatus() {
        val dirty = !readOnly && ::editor.isInitialized && editor.text.toString() != original
        status.text = "$displayName  •  ${charset.name()}${if (dirty) "  •  alterado" else ""}${if (readOnly) "  •  somente leitura" else ""}"
    }

    private fun showError(message: String) = AlertDialog.Builder(this).setTitle("Editor").setMessage(message).setPositiveButton("OK", null).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_EDIT_BYTES = 8 * 1024 * 1024L
        private const val PREVIEW_BYTES = 2 * 1024 * 1024
        private const val HISTORY_TEXT_LIMIT = 2 * 1024 * 1024
    }
}

private fun <T> ArrayDeque<T>.removeLastOrNull(): T? = if (isEmpty()) null else removeLast()
