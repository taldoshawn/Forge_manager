package com.forgemanager.app.features.editor

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import com.forgemanager.app.ForgeApplication
import com.forgemanager.app.core.file.FileLocation
import com.forgemanager.app.core.file.fileDisplayName
import com.forgemanager.app.core.file.putFileLocation
import com.forgemanager.app.core.file.readFileLocation
import com.forgemanager.app.core.ui.ForgeActivity
import com.forgemanager.app.features.settings.UiPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * Dense text/code editor inspired by the MT Manager workflow.
 *
 * The visible UI intentionally stays simple: one dark toolbar, one slim filename
 * strip, the editor itself and a symbol row. Language identity is not shown as
 * an extra banner; language-specific tools remain available from the menus.
 */
class MtTextEditorActivity : ForgeActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var editor: MtLineNumberEditText
    private lateinit var title: TextView
    private lateinit var cursorPosition: TextView
    private lateinit var symbolBar: LinearLayout
    private lateinit var location: FileLocation
    private lateinit var displayName: String
    private lateinit var profile: EditorProfile

    private var charset: Charset = Charsets.UTF_8
    private var bom = ByteArray(0)
    private var lineEnding = "\n"
    private var fileSize = 0L
    private var loading = false
    private var dirty = false
    private var readOnly = false
    private var pagedMode = false
    private var heavyFeatures = true
    private var historyEnabled = true
    private var wordWrap = false
    private var keepScreenOn = false
    private var zooming = false

    private var pageOffset = 0L
    private var pageBytesLoaded = 0

    private var historyCurrent = ""
    private val undo = ArrayDeque<String>()
    private val redo = ArrayDeque<String>()
    private var pendingHighlightStart = Int.MAX_VALUE
    private var pendingHighlightEnd = 0

    private val historyTask = Runnable { commitHistorySnapshot() }
    private val highlightTask = Runnable {
        if (!::editor.isInitialized || loading || zooming || !heavyFeatures || pagedMode) return@Runnable
        val start = editor.selectionStart.coerceAtLeast(0)
        val end = editor.selectionEnd.coerceAtLeast(0)
        val from = pendingHighlightStart.takeIf { it != Int.MAX_VALUE } ?: start
        val to = pendingHighlightEnd.coerceAtLeast(from + 1)
        pendingHighlightStart = Int.MAX_VALUE
        pendingHighlightEnd = 0
        runCatching { SyntaxHighlighter.applyChanged(editor.text, displayName, from, to) }
        if (start <= editor.length() && end <= editor.length()) {
            runCatching { editor.setSelection(start, end) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        location = intent.readFileLocation() ?: run { finish(); return }
        displayName = intent.fileDisplayName()
            ?: location.displayPath.substringAfterLast('/').substringAfterLast("!/").ifBlank { "arquivo" }
        profile = EditorProfile.forFile(displayName)
        setContentView(buildUi())
        loadInitial()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    @Deprecated("Android back compatibility")
    override fun onBackPressed() = requestClose()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiPreferences.surface(this@MtTextEditorActivity))
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(1), 0, dp(1), 0)
            setBackgroundColor(TOOLBAR)
        }
        toolbar.addView(iconButton("☰", "Menu") { showMainMenu(it) })
        toolbar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        toolbar.addView(iconButton("◆", "Manter tela ligada") { toggleKeepScreenOn() })
        toolbar.addView(iconButton("↶", "Desfazer") { performUndo() })
        toolbar.addView(iconButton("↷", "Refazer") { performRedo() })
        toolbar.addView(iconButton("▣", "Salvar") { save() })
        toolbar.addView(iconButton("✎", "Editar") { focusEditor() })
        toolbar.addView(iconButton("⋮", "Mais") { showOverflow(it) })
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(50)))

        val fileStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), 0, dp(7), 0)
            setBackgroundColor(FILE_STRIP)
        }
        title = TextView(this).apply {
            text = displayName
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            textSize = 10.5f
            setTextColor(Color.rgb(205, 205, 205))
        }
        cursorPosition = TextView(this).apply {
            text = "1:1"
            maxLines = 1
            textSize = 10f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTextColor(Color.rgb(175, 175, 175))
        }
        fileStrip.addView(title, LinearLayout.LayoutParams(0, -1, 1f))
        fileStrip.addView(cursorPosition, LinearLayout.LayoutParams(-2, -1))
        root.addView(fileStrip, LinearLayout.LayoutParams(-1, dp(26)))

        editor = MtLineNumberEditText(this).apply {
            applyPalette(
                UiPreferences.surface(this@MtTextEditorActivity),
                UiPreferences.textPrimary(this@MtTextEditorActivity),
                UiPreferences.textSecondary(this@MtTextEditorActivity)
            )
            setSelectAllOnFocus(false)
            setWordWrapEnabled(wordWrap)
            onSelectionChangedListener = { _, _ -> updateCursorInfo() }
            onZoomStateChanged = { active ->
                zooming = active
                if (active) {
                    handler.removeCallbacks(historyTask)
                    handler.removeCallbacks(highlightTask)
                }
            }
            onZoomFinished = {
                zooming = false
                updateCursorInfo()
            }
        }
        editor.addTextChangedListener(editorWatcher())
        root.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))

        symbolBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(1), 0, dp(1), 0)
            setBackgroundColor(symbolSurface())
        }
        val symbolScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(symbolBar)
        }
        root.addView(symbolScroll, LinearLayout.LayoutParams(-1, dp(40)))
        populateSymbolBar()
        return root
    }

    private fun iconButton(label: String, description: String, action: (View) -> Unit) = TextView(this).apply {
        text = label
        contentDescription = description
        textSize = if (label == "⋮") 25f else 20f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(0, 0, 0, 0)
        isClickable = true
        isFocusable = true
        setOnClickListener(action)
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(48))
    }

    private fun symbolButton(label: String, enabled: Boolean = true, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(if (enabled) symbolTextColor() else Color.rgb(130, 130, 130))
        minWidth = dp(38)
        minimumWidth = dp(38)
        setPadding(dp(7), 0, dp(7), 0)
        isEnabled = enabled
        setOnClickListener { if (enabled) action() }
    }

    private fun populateSymbolBar() {
        if (!::symbolBar.isInitialized) return
        symbolBar.removeAllViews()
        if (pagedMode) {
            symbolBar.addView(symbolButton("◀") { previousPage() })
            symbolBar.addView(symbolButton("▶") { nextPage() })
            symbolBar.addView(symbolButton("⌕") { showSearch() })
            symbolBar.addView(symbolButton("⧉") { editor.selectAll() })
            return
        }
        val symbols = if (profile.symbolBar.isEmpty()) {
            listOf("TAB", "/", "+", "-", "*", "=", "<", ">", "\"", "'", ";", "|")
        } else profile.symbolBar
        for (symbol in symbols) {
            symbolBar.addView(symbolButton(symbol, enabled = !readOnly) { insertSymbol(symbol) })
        }
    }

    private fun editorWatcher() = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            if (zooming) return
            pendingHighlightStart = minOf(pendingHighlightStart, start)
            pendingHighlightEnd = maxOf(pendingHighlightEnd, start + count)
        }

        override fun afterTextChanged(s: Editable?) {
            if (s == null || loading || readOnly || zooming) return
            dirty = true
            scheduleHistorySnapshot()
            scheduleHighlight()
            updateTitle()
            updateCursorInfo()
        }
    }

    private fun scheduleHistorySnapshot() {
        if (!historyEnabled || loading || zooming) return
        handler.removeCallbacks(historyTask)
        handler.postDelayed(historyTask, HISTORY_DEBOUNCE_MS)
    }

    private fun commitHistorySnapshot() {
        if (!historyEnabled || loading || zooming || !::editor.isInitialized) return
        val now = editor.text.toString()
        if (now == historyCurrent) return
        undo.addLast(historyCurrent)
        while (undo.size > MAX_HISTORY) undo.removeFirst()
        historyCurrent = now
        redo.clear()
    }

    private fun scheduleHighlight() {
        if (!heavyFeatures || pagedMode || zooming) return
        handler.removeCallbacks(highlightTask)
        handler.postDelayed(highlightTask, HIGHLIGHT_DEBOUNCE_MS)
    }

    private fun loadInitial() {
        title.text = displayName
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val backend = graph.resolver.backendFor(location)
                    fileSize = backend.stat(location).size.coerceAtLeast(0)
                    pagedMode = fileSize > MAX_EDITABLE_BYTES
                    readOnly = pagedMode
                    heavyFeatures = !pagedMode && fileSize <= HIGHLIGHT_BYTES
                    historyEnabled = !pagedMode && fileSize <= HISTORY_BYTES

                    if (pagedMode) {
                        pageOffset = 0L
                        val bytes = readPageBytes(0L)
                        pageBytesLoaded = bytes.size
                        decodeChunk(bytes, firstPage = true)
                    } else {
                        val bytes = backend.openInput(location).use { readAtMost(it, MAX_EDITABLE_BYTES.toInt() + 1) }
                        if (bytes.size > MAX_EDITABLE_BYTES) error("Arquivo excedeu o limite de edição segura")
                        detectEncoding(bytes)
                    }
                }
            }
            result.onSuccess { applyLoadedText(it, initial = true) }
                .onFailure(::showOpenFailure)
        }
    }

    private fun applyLoadedText(text: String, initial: Boolean) {
        loading = true
        editor.setText(text)
        editor.setSelection(0)
        if (readOnly) {
            editor.keyListener = null
            editor.setTextIsSelectable(true)
        }
        loading = false

        dirty = false
        undo.clear()
        redo.clear()
        historyCurrent = if (historyEnabled) text else ""
        if (heavyFeatures) runCatching { SyntaxHighlighter.apply(editor.text, displayName) }
        populateSymbolBar()
        updateTitle()
        updateCursorInfo()

        if (initial && pagedMode) {
            toast("Arquivo grande: visualização por blocos ativada para evitar travamentos")
        } else if (initial && !heavyFeatures && !pagedMode) {
            toast("Modo leve ativado para manter o editor responsivo")
        }
    }

    private suspend fun readPageBytes(offset: Long): ByteArray {
        val target = offset.coerceIn(0L, fileSize.coerceAtLeast(0L))
        val direct = (location as? FileLocation.Direct)?.path?.let(::File)
        if (direct != null && direct.isFile && direct.canRead()) {
            RandomAccessFile(direct, "r").use { raf ->
                raf.seek(target)
                val max = minOf(PAGE_BYTES.toLong(), (fileSize - target).coerceAtLeast(0L)).toInt()
                val buffer = ByteArray(max)
                var total = 0
                while (total < max) {
                    val read = raf.read(buffer, total, max - total)
                    if (read < 0) break
                    total += read
                }
                return if (total == buffer.size) buffer else buffer.copyOf(total)
            }
        }
        val backend = graph.resolver.backendFor(location)
        return backend.openInput(location).use { input ->
            skipFully(input, target)
            readAtMost(input, PAGE_BYTES)
        }
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        val scratch = ByteArray(32 * 1024)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            val read = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (read < 0) break
            remaining -= read
        }
    }

    private fun readAtMost(input: InputStream, limit: Int): ByteArray {
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

    private fun detectEncoding(data: ByteArray): String {
        val decoded = when {
            data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte() -> {
                charset = Charsets.UTF_8
                bom = data.copyOfRange(0, 3)
                data.copyOfRange(3, data.size).toString(charset)
            }
            data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte() -> {
                charset = Charsets.UTF_16LE
                bom = data.copyOfRange(0, 2)
                data.copyOfRange(2, data.size).toString(charset)
            }
            data.size >= 2 && data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte() -> {
                charset = Charsets.UTF_16BE
                bom = data.copyOfRange(0, 2)
                data.copyOfRange(2, data.size).toString(charset)
            }
            else -> {
                charset = Charsets.UTF_8
                bom = ByteArray(0)
                data.toString(charset)
            }
        }
        lineEnding = when {
            "\r\n" in decoded -> "\r\n"
            '\r' in decoded -> "\r"
            else -> "\n"
        }
        return normalizeNewlines(decoded)
    }

    private fun decodeChunk(data: ByteArray, firstPage: Boolean): String {
        if (firstPage) return detectEncoding(data)
        return normalizeNewlines(data.toString(charset))
    }

    private fun normalizeNewlines(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n')

    private fun nextPage() {
        if (!pagedMode || pageBytesLoaded <= 0) return
        val next = pageOffset + pageBytesLoaded
        if (next >= fileSize) {
            toast("Último bloco")
            return
        }
        loadPage(next)
    }

    private fun previousPage() {
        if (!pagedMode) return
        if (pageOffset <= 0L) {
            toast("Primeiro bloco")
            return
        }
        loadPage((pageOffset - PAGE_BYTES).coerceAtLeast(0L))
    }

    private fun loadPage(offset: Long) {
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val target = offset.coerceIn(0L, fileSize.coerceAtLeast(0L))
                    target to readPageBytes(target)
                }
            }
            result.onSuccess { (target, bytes) ->
                pageOffset = target
                pageBytesLoaded = bytes.size
                applyLoadedText(decodeChunk(bytes, firstPage = target == 0L), initial = false)
            }.onFailure { showError(it.message ?: "Falha ao carregar bloco") }
        }
    }

    private fun save(after: (() -> Unit)? = null) {
        if (pagedMode || readOnly) {
            showError("Este arquivo está em visualização por blocos. A edição completa foi desativada para evitar travamentos e perda de dados.")
            return
        }
        handler.removeCallbacks(historyTask)
        val content = editor.text.toString()
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { writeLocation(location, content) } }
                .onSuccess {
                    dirty = false
                    historyCurrent = if (historyEnabled) content else ""
                    fileSize = bom.size + encodedText(content).size.toLong()
                    updateTitle()
                    toast("Salvo")
                    after?.invoke()
                }.onFailure { showError(it.message ?: "Falha ao salvar") }
        }
    }

    private suspend fun writeLocation(target: FileLocation, content: String) {
        val direct = target as? FileLocation.Direct
        if (direct != null) {
            atomicWriteDirect(File(direct.path), content)
            return
        }
        val backend = graph.resolver.backendFor(target, write = true)
        backend.openOutput(target, truncate = true).use { output ->
            output.write(bom)
            output.write(encodedText(content))
            output.flush()
        }
    }

    private fun atomicWriteDirect(file: File, content: String) {
        val parent = file.parentFile ?: error("Pasta pai não encontrada")
        require(parent.isDirectory) { "Pasta pai inválida" }
        val temp = File(parent, ".${file.name}.forge-${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(bom)
                output.write(encodedText(content))
                output.flush()
                output.fd.sync()
            }
            if (file.exists()) {
                runCatching {
                    val mode = Os.stat(file.path).st_mode and 0x1FF
                    Os.chmod(temp.path, mode)
                }
            }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Throwable) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun encodedText(content: String): ByteArray {
        val normalized = when (lineEnding) {
            "\r\n" -> content.replace("\n", "\r\n")
            "\r" -> content.replace("\n", "\r")
            else -> content
        }
        return normalized.toByteArray(charset)
    }

    private fun saveAs() {
        if (readOnly || pagedMode) return
        val input = EditText(this).apply {
            setText(displayName)
            setSelection(text.length)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Salvar como")
            .setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isBlank() || '/' in newName || '\\' in newName) {
                    showError("Nome inválido")
                    return@setPositiveButton
                }
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val backend = graph.resolver.backendFor(location, write = true)
                            val parent = backend.parent(location) ?: error("Pasta pai indisponível")
                            val created = backend.create(parent, newName)
                            writeLocation(created.location, editor.text.toString())
                            created.location
                        }
                    }.onSuccess { newLocation ->
                        location = newLocation
                        displayName = newName
                        profile = EditorProfile.forFile(newName)
                        dirty = false
                        populateSymbolBar()
                        updateTitle()
                        toast("Salvo como $newName")
                    }.onFailure { showError(it.message ?: "Falha em Salvar como") }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun formatDocument() {
        if (!allowHeavyAction("formatar")) return
        val source = editor.text.toString()
        scope.launch {
            val result = withContext(Dispatchers.Default) { EditorTools.format(profile, source) }
            result.onSuccess { replaceWholeDocument(it, "Documento formatado") }
                .onFailure { showError(it.message ?: "Falha ao formatar") }
        }
    }

    private fun validateDocument() {
        if (!allowHeavyAction("validar")) return
        val source = editor.text.toString()
        scope.launch {
            val result = withContext(Dispatchers.Default) { EditorTools.validate(profile, source) }
            val where = buildString {
                result.line?.let { append("\nLinha: $it") }
                result.column?.let { append("  Coluna: $it") }
            }
            AlertDialog.Builder(this@MtTextEditorActivity)
                .setTitle(if (result.ok) "Validação concluída" else "Erro de validação")
                .setMessage(result.message + where)
                .setPositiveButton(if (result.line != null) "Ir para linha" else "OK") { _, _ -> result.line?.let(::goToLine) }
                .setNegativeButton(if (result.line != null) "Fechar" else "", null)
                .show()
        }
    }

    private fun showStructure() {
        if (!allowHeavyAction("analisar estrutura")) return
        val source = editor.text.toString()
        scope.launch {
            val symbols = withContext(Dispatchers.Default) { EditorTools.symbols(profile, source) }
            if (symbols.isNotEmpty()) {
                AlertDialog.Builder(this@MtTextEditorActivity)
                    .setTitle("Símbolos")
                    .setItems(symbols.take(1_000).map { "${it.line}  ${it.kind}  ${it.name}" }.toTypedArray()) { _, which ->
                        symbols.getOrNull(which)?.let { goToLine(it.line) }
                    }
                    .setNegativeButton("Fechar", null)
                    .show()
            } else {
                val result = withContext(Dispatchers.Default) { EditorTools.structure(profile, source) }
                result.onSuccess {
                    AlertDialog.Builder(this@MtTextEditorActivity)
                        .setTitle("Estrutura")
                        .setMessage(it)
                        .setPositiveButton("Fechar", null)
                        .show()
                }.onFailure { showError(it.message ?: "Falha ao analisar") }
            }
        }
    }

    private fun preview() {
        if (profile.language !in setOf(EditorLanguage.HTML, EditorLanguage.MARKDOWN) || pagedMode) return
        val open = { startActivity(Intent(this, HtmlPreviewActivity::class.java).putFileLocation(location, displayName)) }
        if (dirty) save(open) else open()
    }

    private fun allowHeavyAction(action: String): Boolean {
        if (pagedMode || readOnly) {
            showError("Não é possível $action no modo de arquivo grande por blocos")
            return false
        }
        if (!heavyFeatures) {
            showError("$action foi desativado neste arquivo para manter o editor responsivo")
            return false
        }
        return true
    }

    private fun replaceWholeDocument(value: String, message: String) {
        if (readOnly || pagedMode || value == editor.text.toString()) return
        loading = true
        editor.setText(value)
        editor.setSelection(value.length.coerceAtMost(editor.length()))
        loading = false
        dirty = true
        if (historyEnabled) {
            undo.addLast(historyCurrent)
            while (undo.size > MAX_HISTORY) undo.removeFirst()
            historyCurrent = value
            redo.clear()
        }
        if (heavyFeatures) runCatching { SyntaxHighlighter.apply(editor.text, displayName) }
        updateTitle()
        updateCursorInfo()
        toast(message)
    }

    private fun showSearch() {
        val dialog = AlertDialog.Builder(this).setTitle(if (pagedMode) "Buscar no bloco" else "Buscar e substituir").create()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(10))
        }
        val find = EditText(this).apply { hint = "Buscar"; setSingleLine() }
        val replacement = EditText(this).apply { hint = "Substituir por"; setSingleLine(); isEnabled = !readOnly }
        box.addView(find)
        box.addView(replacement)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun action(label: String, enabled: Boolean = true, run: () -> Unit) {
            actions.addView(TextView(this@MtTextEditorActivity).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 11f
                isEnabled = enabled
                setOnClickListener { if (enabled) run() }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        action("ANT.") { findPrevious(find.text.toString()) }
        action("PRÓX.") { findNext(find.text.toString()) }
        action("TROCAR", !readOnly) { replaceCurrent(find.text.toString(), replacement.text.toString()) }
        action("TODOS", !readOnly) { replaceAll(find.text.toString(), replacement.text.toString()) }
        box.addView(actions)
        dialog.setView(box)
        dialog.setButton(AlertDialog.BUTTON_NEGATIVE, "FECHAR") { _, _ -> dialog.dismiss() }
        dialog.setOnShowListener { find.requestFocus() }
        dialog.show()
    }

    private fun findNext(query: String) = find(query, true)
    private fun findPrevious(query: String) = find(query, false)

    private fun find(query: String, forward: Boolean) {
        if (query.isEmpty()) return
        val text = editor.text.toString()
        val index = if (forward) {
            val from = editor.selectionEnd.coerceAtLeast(0)
            text.indexOf(query, from, ignoreCase = true).let { if (it < 0) text.indexOf(query, 0, true) else it }
        } else {
            val from = (editor.selectionStart - 1).coerceAtMost(text.lastIndex)
            text.lastIndexOf(query, from, ignoreCase = true).let { if (it < 0) text.lastIndexOf(query, text.lastIndex, true) else it }
        }
        if (index >= 0) {
            editor.requestFocus()
            editor.setSelection(index, (index + query.length).coerceAtMost(editor.length()))
        } else toast("Não encontrado${if (pagedMode) " neste bloco" else ""}")
    }

    private fun replaceCurrent(query: String, replacement: String) {
        if (query.isEmpty() || readOnly) return
        val start = editor.selectionStart
        val end = editor.selectionEnd
        if (start >= 0 && end > start && editor.text.subSequence(start, end).toString().equals(query, true)) {
            editor.text.replace(start, end, replacement)
            editor.setSelection((start + replacement.length).coerceAtMost(editor.length()))
        } else findNext(query)
    }

    private fun replaceAll(pattern: String, replacement: String) {
        if (pattern.isEmpty() || readOnly) return
        val source = editor.text.toString()
        val result = if (pattern.startsWith('/') && pattern.length > 1) {
            runCatching { Regex(pattern.drop(1)).replace(source, replacement) }
                .getOrElse { showError("Regex inválida: ${it.message}"); return }
        } else source.replace(pattern, replacement, ignoreCase = false)
        replaceWholeDocument(result, "Substituição concluída")
    }

    private fun performUndo() {
        if (!historyEnabled || readOnly) return
        handler.removeCallbacks(historyTask)
        commitHistorySnapshot()
        val value = undo.removeLastOrNull() ?: return
        redo.addLast(editor.text.toString())
        setEditorSnapshot(value)
    }

    private fun performRedo() {
        if (!historyEnabled || readOnly) return
        val value = redo.removeLastOrNull() ?: return
        undo.addLast(editor.text.toString())
        setEditorSnapshot(value)
    }

    private fun setEditorSnapshot(value: String) {
        loading = true
        editor.setText(value)
        editor.setSelection(value.length.coerceAtMost(editor.length()))
        loading = false
        historyCurrent = value
        dirty = true
        if (heavyFeatures) runCatching { SyntaxHighlighter.apply(editor.text, displayName) }
        updateTitle()
        updateCursorInfo()
    }

    private fun showMainMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Buscar")
            menu.add("Ir para linha")
            if (pagedMode) {
                menu.add("Bloco anterior")
                menu.add("Próximo bloco")
            } else {
                if (profile.canFormat) menu.add("Formatar")
                if (profile.canValidate) menu.add("Validar")
                if (profile.canOutline) menu.add("Símbolos")
                if (profile.canPreview) menu.add("Preview")
                if (!readOnly) menu.add("Salvar como")
            }
            menu.add("Fechar")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Buscar" -> showSearch()
                    "Ir para linha" -> promptGoToLine()
                    "Bloco anterior" -> previousPage()
                    "Próximo bloco" -> nextPage()
                    "Formatar" -> formatDocument()
                    "Validar" -> validateDocument()
                    "Símbolos" -> showStructure()
                    "Preview" -> preview()
                    "Salvar como" -> saveAs()
                    "Fechar" -> requestClose()
                }
                true
            }
            show()
        }
    }

    private fun showOverflow(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Buscar e substituir")
            menu.add(if (wordWrap) "Desativar quebra de linha" else "Ativar quebra de linha")
            menu.add("Aumentar fonte")
            menu.add("Diminuir fonte")
            if (!readOnly) menu.add("Encoding")
            if (!readOnly) menu.add("Final de linha")
            menu.add("Copiar")
            if (!readOnly) menu.add("Recortar")
            if (!readOnly) menu.add("Colar")
            menu.add("Selecionar tudo")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Buscar e substituir" -> showSearch()
                    "Ativar quebra de linha", "Desativar quebra de linha" -> {
                        wordWrap = !wordWrap
                        editor.setWordWrapEnabled(wordWrap)
                    }
                    "Aumentar fonte" -> editor.setEditorZoomSp(editor.editorZoomSp() + 1f)
                    "Diminuir fonte" -> editor.setEditorZoomSp(editor.editorZoomSp() - 1f)
                    "Encoding" -> chooseEncoding()
                    "Final de linha" -> chooseLineEnding()
                    "Copiar" -> copySelection(false)
                    "Recortar" -> copySelection(true)
                    "Colar" -> pasteClipboard()
                    "Selecionar tudo" -> editor.selectAll()
                }
                true
            }
            show()
        }
    }

    private fun promptGoToLine() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = if (pagedMode) "Linha dentro do bloco" else "Linha"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Ir para linha")
            .setView(input)
            .setPositiveButton("Ir") { _, _ -> input.text.toString().toIntOrNull()?.let(::goToLine) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun goToLine(line: Int) {
        if (line <= 0) return
        val layout = editor.layout
        if (layout != null && layout.lineCount > 0) {
            val index = (line - 1).coerceIn(0, layout.lineCount - 1)
            val offset = layout.getLineStart(index).coerceIn(0, editor.length())
            editor.requestFocus()
            editor.setSelection(offset)
            editor.bringPointIntoView(offset)
        }
    }

    private fun chooseEncoding() {
        val names = arrayOf("UTF-8", "UTF-16LE", "UTF-16BE")
        AlertDialog.Builder(this).setTitle("Encoding ao salvar").setItems(names) { _, which ->
            charset = when (which) { 1 -> Charsets.UTF_16LE; 2 -> Charsets.UTF_16BE; else -> Charsets.UTF_8 }
            bom = when (which) {
                1 -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
                2 -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
                else -> ByteArray(0)
            }
            dirty = true
        }.show()
    }

    private fun chooseLineEnding() {
        val values = arrayOf("LF (Unix/Android)", "CRLF (Windows)", "CR (clássico)")
        AlertDialog.Builder(this).setTitle("Final de linha").setItems(values) { _, which ->
            lineEnding = when (which) { 1 -> "\r\n"; 2 -> "\r"; else -> "\n" }
            dirty = true
        }.show()
    }

    private fun insertSymbol(raw: String) {
        if (readOnly) return
        val symbol = if (raw == "TAB") "    " else raw
        val start = editor.selectionStart.coerceAtLeast(0)
        val end = editor.selectionEnd.coerceAtLeast(start)
        editor.text.replace(start, end, symbol)
        editor.setSelection((start + symbol.length).coerceAtMost(editor.length()))
    }

    private fun copySelection(cut: Boolean) {
        val start = editor.selectionStart.coerceAtLeast(0)
        val end = editor.selectionEnd.coerceAtLeast(start)
        if (end <= start) return
        val value = editor.text.subSequence(start, end).toString()
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Forge Manager", value))
        if (cut && !readOnly) editor.text.delete(start, end)
    }

    private fun pasteClipboard() {
        if (readOnly) return
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = clipboard.primaryClip ?: return
        val value = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
        val start = editor.selectionStart.coerceAtLeast(0)
        val end = editor.selectionEnd.coerceAtLeast(start)
        editor.text.replace(start, end, value)
        editor.setSelection((start + value.length).coerceAtMost(editor.length()))
    }

    private fun focusEditor() {
        if (readOnly) {
            toast("Arquivo grande aberto em modo somente leitura")
            return
        }
        editor.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun toggleKeepScreenOn() {
        keepScreenOn = !keepScreenOn
        if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        toast(if (keepScreenOn) "Tela mantida ligada" else "Tela normal")
    }

    private fun requestClose() {
        if (!dirty || readOnly) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Salvar alterações?")
            .setMessage(displayName)
            .setPositiveButton("Salvar") { _, _ -> save { finish() } }
            .setNegativeButton("Descartar") { _, _ -> finish() }
            .setNeutralButton("Cancelar", null)
            .show()
    }

    private fun updateTitle() {
        if (!::title.isInitialized) return
        title.text = displayName + if (dirty) " *" else ""
    }

    private fun updateCursorInfo() {
        if (!::editor.isInitialized || !::cursorPosition.isInitialized) return
        val pos = editor.selectionStart.coerceIn(0, editor.length())
        val layout = editor.layout
        val lineIndex = if (layout != null && layout.lineCount > 0) {
            layout.getLineForOffset(pos.coerceAtMost(editor.length()))
        } else 0
        val lineStart = if (layout != null && layout.lineCount > 0) layout.getLineStart(lineIndex) else 0
        val column = (pos - lineStart + 1).coerceAtLeast(1)
        cursorPosition.text = if (pagedMode) {
            val page = if (PAGE_BYTES > 0) (pageOffset / PAGE_BYTES) + 1 else 1
            val pages = ((fileSize + PAGE_BYTES - 1) / PAGE_BYTES).coerceAtLeast(1)
            "${lineIndex + 1}:$column   $page/$pages"
        } else "${lineIndex + 1}:$column"
    }

    private fun showOpenFailure(error: Throwable) {
        val raw = error.message.orEmpty()
        val denied = raw.contains("permission", true) || raw.contains("denied", true) ||
            raw.contains("bloqueou", true) || raw.contains("autorize", true)
        val message = if (denied) {
            "Não foi possível abrir este arquivo porque o Android bloqueou o acesso.\n\n${location.displayPath}\n\nAutorize Shizuku, root ou uma pasta via SAF e toque em Tentar novamente."
        } else {
            "Não foi possível abrir este arquivo.\n\n${location.displayPath}\n\n${raw.ifBlank { error::class.java.simpleName }}"
        }
        AlertDialog.Builder(this)
            .setTitle(if (denied) "Sem permissão" else "Falha ao abrir")
            .setMessage(message)
            .setPositiveButton("Tentar novamente") { _, _ -> loadInitial() }
            .setNeutralButton("Copiar caminho") { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Caminho", location.displayPath))
            }
            .setNegativeButton("Fechar") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showError(message: String) = AlertDialog.Builder(this)
        .setTitle("Editor")
        .setMessage(message)
        .setPositiveButton("OK", null)
        .show()

    private fun symbolSurface(): Int = if (UiPreferences.isLight(this)) Color.rgb(245, 245, 245) else Color.rgb(38, 38, 38)
    private fun symbolTextColor(): Int = if (UiPreferences.isLight(this)) Color.rgb(45, 45, 45) else Color.rgb(230, 230, 230)
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val TOOLBAR = Color.rgb(45, 45, 45)
        private val FILE_STRIP = Color.rgb(38, 38, 38)
        private const val MAX_EDITABLE_BYTES = 2 * 1024 * 1024
        private const val PAGE_BYTES = 256 * 1024
        private const val HIGHLIGHT_BYTES = 512 * 1024L
        private const val HISTORY_BYTES = 512 * 1024L
        private const val MAX_HISTORY = 16
        private const val HISTORY_DEBOUNCE_MS = 500L
        private const val HIGHLIGHT_DEBOUNCE_MS = 260L
    }
}

private fun <T> ArrayDeque<T>.removeLastOrNull(): T? = if (isEmpty()) null else removeLast()
