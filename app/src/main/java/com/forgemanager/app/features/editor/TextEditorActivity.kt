package com.forgemanager.app.features.editor

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
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
import android.widget.Button
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
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class TextEditorActivity : ForgeActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var editor: LineNumberEditText
    private lateinit var title: TextView
    private lateinit var info: TextView
    private lateinit var location: FileLocation
    private lateinit var displayName: String
    private lateinit var profile: EditorProfile
    private lateinit var profileActions: LinearLayout
    private lateinit var symbolBar: LinearLayout

    private var charset: Charset = Charsets.UTF_8
    private var bom = ByteArray(0)
    private var lineEnding = "\n"
    private var original = ""
    private var loading = false
    private var readOnly = false
    private var largeFileMode = false
    private var heavyFeatures = true
    private var wordWrap = false
    private var fileSize = 0L

    private val undo = ArrayDeque<String>()
    private val redo = ArrayDeque<String>()
    private var previous = ""
    private var lastChangeStart = 0
    private var lastChangeBefore = 0
    private var lastChangeCount = 0
    private var pendingHighlightStart = Int.MAX_VALUE
    private var pendingHighlightEnd = 0

    private val highlightTask = Runnable {
        if (!::editor.isInitialized || loading || !heavyFeatures) return@Runnable
        val start = editor.selectionStart.coerceAtLeast(0)
        val end = editor.selectionEnd.coerceAtLeast(0)
        val from = pendingHighlightStart.takeIf { it != Int.MAX_VALUE } ?: start
        val to = pendingHighlightEnd.coerceAtLeast(from + 1)
        pendingHighlightStart = Int.MAX_VALUE
        pendingHighlightEnd = 0
        SyntaxHighlighter.applyChanged(editor.text, displayName, from, to)
        if (start <= editor.length() && end <= editor.length()) runCatching { editor.setSelection(start, end) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        location = intent.readFileLocation() ?: run { finish(); return }
        displayName = intent.fileDisplayName()
            ?: location.displayPath.substringAfterLast('/').substringAfterLast("!/").ifBlank { "arquivo" }
        profile = EditorProfile.forFile(displayName)
        setContentView(buildUi())
        load()
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
            setBackgroundColor(UiPreferences.background(this@TextEditorActivity))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), 0)
            setBackgroundColor(UiPreferences.surface(this@TextEditorActivity))
        }
        top.addView(topButton("←", "Voltar") { requestClose() })
        title = TextView(this).apply {
            text = displayName
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            textSize = 12.5f
            setTextColor(UiPreferences.textPrimary(this@TextEditorActivity))
            setPadding(dp(4), 0, dp(2), 0)
        }
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(topButton("✓", "Salvar") { save() })
        top.addView(topButton("↶", "Desfazer") { performUndo() })
        top.addView(topButton("↷", "Refazer") { performRedo() })
        top.addView(topButton("⌕", "Buscar e substituir") { showSearch() })
        top.addView(topButton("⋮", "Mais") { showOverflow(it) })
        root.addView(top, LinearLayout.LayoutParams(-1, dp(52)))

        profileActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
            setBackgroundColor(UiPreferences.elevatedSurface(this@TextEditorActivity))
        }
        val actionScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(profileActions)
        }
        root.addView(actionScroll, LinearLayout.LayoutParams(-1, dp(40)))
        populateProfileActions()

        editor = LineNumberEditText(this).apply {
            applyPalette(
                UiPreferences.background(this@TextEditorActivity),
                UiPreferences.textPrimary(this@TextEditorActivity),
                UiPreferences.textSecondary(this@TextEditorActivity),
                UiPreferences.divider(this@TextEditorActivity)
            )
            setSelectAllOnFocus(false)
            setWordWrapEnabled(wordWrap)
            onSelectionChangedListener = { _, _ -> updateInfo() }
        }
        editor.addTextChangedListener(editorWatcher())
        root.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))

        symbolBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            setBackgroundColor(UiPreferences.elevatedSurface(this@TextEditorActivity))
        }
        val symbolScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(symbolBar)
        }
        root.addView(symbolScroll, LinearLayout.LayoutParams(-1, dp(42)))
        populateSymbolBar()

        info = TextView(this).apply {
            textSize = 10f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            setTextColor(UiPreferences.textSecondary(this@TextEditorActivity))
            setBackgroundColor(UiPreferences.surface(this@TextEditorActivity))
        }
        root.addView(info, LinearLayout.LayoutParams(-1, dp(26)))
        return root
    }

    private fun topButton(label: String, description: String, action: (View) -> Unit) = Button(this).apply {
        text = label
        contentDescription = description
        textSize = if (label.length == 1) 16f else 10f
        minWidth = dp(40)
        minimumWidth = dp(40)
        setPadding(dp(3), 0, dp(3), 0)
        setTextColor(UiPreferences.textPrimary(this@TextEditorActivity))
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener(action)
    }

    private fun smallAction(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 9.5f
        minWidth = dp(58)
        minimumWidth = dp(58)
        setPadding(dp(8), 0, dp(8), 0)
        setTextColor(UiPreferences.textPrimary(this@TextEditorActivity))
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { action() }
    }

    private fun populateProfileActions() {
        profileActions.removeAllViews()
        profileActions.addView(TextView(this).apply {
            text = profile.label.uppercase()
            textSize = 9f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(UiPreferences.accent(this@TextEditorActivity))
            setPadding(dp(7), 0, dp(8), 0)
        })
        if (profile.canFormat) profileActions.addView(smallAction("FORMATAR") { formatDocument() })
        if (profile.canValidate) profileActions.addView(smallAction("VALIDAR") { validateDocument() })
        if (profile.canOutline) profileActions.addView(smallAction(if (profile.language in setOf(EditorLanguage.XML, EditorLanguage.JSON)) "ÁRVORE" else "SÍMBOLOS") { showStructure() })
        if (profile.canPreview) profileActions.addView(smallAction("PREVIEW") { preview() })
        if (profile.language in setOf(EditorLanguage.XML, EditorLanguage.JSON)) {
            profileActions.addView(smallAction("MINIFICAR") { minifyDocument() })
        }
        if (profileActions.childCount == 1) {
            profileActions.addView(TextView(this).apply {
                text = "edição simples"
                textSize = 10f
                setTextColor(UiPreferences.textSecondary(this@TextEditorActivity))
                setPadding(dp(4), 0, dp(8), 0)
            })
        }
    }

    private fun populateSymbolBar() {
        symbolBar.removeAllViews()
        for (symbol in profile.symbolBar) {
            symbolBar.addView(Button(this).apply {
                text = symbol
                textSize = 11f
                minWidth = dp(42)
                minimumWidth = dp(42)
                setPadding(dp(7), 0, dp(7), 0)
                setTextColor(UiPreferences.textPrimary(this@TextEditorActivity))
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { insertSymbol(symbol) }
            })
        }
    }

    private fun editorWatcher() = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            lastChangeStart = start
            lastChangeBefore = before
            lastChangeCount = count
            pendingHighlightStart = minOf(pendingHighlightStart, start)
            pendingHighlightEnd = maxOf(pendingHighlightEnd, start + count)
        }

        override fun afterTextChanged(s: Editable?) {
            if (s == null || loading) return
            if (!readOnly) {
                val now = s.toString()
                if (previous != now) {
                    if (!largeFileMode && previous.length <= HISTORY_TEXT_LIMIT) {
                        undo.addLast(previous)
                        while (undo.size > MAX_HISTORY) undo.removeFirst()
                    }
                    redo.clear()
                    previous = now
                }
                applyTypingAssistance()
                previous = editor.text.toString()
            }
            updateTitle()
            updateInfo()
            scheduleHighlight()
        }
    }

    private fun applyTypingAssistance() {
        if (loading || readOnly || lastChangeBefore != 0 || lastChangeCount != 1) return
        val text = editor.text
        val pos = lastChangeStart
        if (pos !in 0 until text.length) return
        val typed = text[pos]
        if (typed == '\n') {
            val before = text.subSequence(0, pos).toString()
            val previousLine = before.substringAfterLast('\n')
            val baseIndent = previousLine.takeWhile { it == ' ' || it == '\t' }
            val needsExtra = profile.indentationAfterColon && previousLine.trimEnd().endsWith(':') ||
                profile.language in BRACED_LANGUAGES && previousLine.trimEnd().endsWith('{')
            val indent = baseIndent + if (needsExtra) "    " else ""
            if (indent.isNotEmpty()) {
                loading = true
                text.insert(pos + 1, indent)
                editor.setSelection((pos + 1 + indent.length).coerceAtMost(text.length))
                loading = false
            }
            return
        }
        val closing = when (typed) {
            '(' -> ')'
            '[' -> ']'
            '{' -> '}'
            '"' -> '"'
            '\'' -> '\''
            else -> null
        } ?: return
        if (profile.language in setOf(EditorLanguage.PLAIN, EditorLanguage.MARKDOWN) && typed == '{') return
        val cursor = editor.selectionStart
        if (cursor < 0 || cursor > text.length) return
        if (cursor < text.length && text[cursor] == closing) return
        loading = true
        text.insert(cursor, closing.toString())
        editor.setSelection(cursor)
        loading = false
    }

    private fun load() {
        title.text = "Abrindo $displayName…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val backend = graph.resolver.backendFor(location)
                    val size = backend.stat(location).size.coerceAtLeast(0)
                    fileSize = size
                    readOnly = size > MAX_LARGE_EDIT_BYTES
                    largeFileMode = size > MAX_NORMAL_EDIT_BYTES
                    heavyFeatures = !largeFileMode
                    val limit = when {
                        readOnly -> PREVIEW_BYTES
                        else -> MAX_LARGE_EDIT_BYTES.toInt()
                    }
                    val data = backend.openInput(location).use { input -> readAtMost(input, limit) }
                    detectEncoding(data)
                }
            }.onSuccess { decoded ->
                loading = true
                original = decoded
                previous = decoded
                editor.setText(decoded)
                editor.setSelection(0)
                if (readOnly) {
                    editor.keyListener = null
                    editor.setTextIsSelectable(true)
                }
                loading = false
                if (heavyFeatures) SyntaxHighlighter.apply(editor.text, displayName)
                updateTitle()
                updateInfo()
                if (largeFileMode) {
                    val message = if (readOnly) "Arquivo muito grande: prévia parcial somente leitura" else "Large File Mode: highlight e análise pesada desativados"
                    Toast.makeText(this@TextEditorActivity, message, Toast.LENGTH_LONG).show()
                }
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
        return decoded.replace("\r\n", "\n").replace('\r', '\n')
    }

    private fun save(after: (() -> Unit)? = null) {
        if (readOnly) { showError("A prévia parcial não pode ser sobrescrita"); return }
        val content = editor.text.toString()
        title.text = "Salvando $displayName…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { writeLocation(location, content) } }
                .onSuccess {
                    original = content
                    previous = content
                    fileSize = encodedText(content).size.toLong()
                    updateTitle()
                    updateInfo()
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
        if (readOnly) { showError("A prévia parcial não pode ser salva como edição"); return }
        val input = EditText(this).apply {
            setText(displayName)
            setSelection(text.length)
            setSingleLine()
        }
        AlertDialog.Builder(this).setTitle("Salvar como").setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isBlank() || '/' in newName || '\\' in newName) { showError("Nome inválido"); return@setPositiveButton }
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
                        profile = EditorProfile.forFile(displayName)
                        original = editor.text.toString()
                        previous = original
                        populateProfileActions()
                        populateSymbolBar()
                        updateTitle()
                        toast("Salvo como $newName")
                    }.onFailure { showError(it.message ?: "Falha em Salvar como") }
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun formatDocument() {
        if (!allowHeavyAction("formatar")) return
        val source = editor.text.toString()
        scope.launch {
            val result = withContext(Dispatchers.Default) { EditorTools.format(profile, source) }
            result.onSuccess { formatted -> replaceWholeDocument(formatted, "Documento formatado") }
                .onFailure { showError(it.message ?: "Falha ao formatar") }
        }
    }

    private fun minifyDocument() {
        if (!allowHeavyAction("minificar")) return
        val source = editor.text.toString()
        scope.launch {
            val result = withContext(Dispatchers.Default) { EditorTools.minify(profile, source) }
            result.onSuccess { minified -> replaceWholeDocument(minified, "Documento minificado") }
                .onFailure { showError(it.message ?: "Falha ao minificar") }
        }
    }

    private fun validateDocument() {
        if (!allowHeavyAction("validar")) return
        val source = editor.text.toString()
        scope.launch {
            val result = withContext(Dispatchers.Default) { EditorTools.validate(profile, source) }
            val locationText = buildString {
                result.line?.let { append("\nLinha: $it") }
                result.column?.let { append("  Coluna: $it") }
            }
            AlertDialog.Builder(this@TextEditorActivity)
                .setTitle(if (result.ok) "Validação concluída" else "Erro de validação")
                .setMessage(result.message + locationText)
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
            if (symbols.isNotEmpty() && profile.language !in setOf(EditorLanguage.XML, EditorLanguage.JSON)) {
                AlertDialog.Builder(this@TextEditorActivity).setTitle("Símbolos — ${profile.label}")
                    .setItems(symbols.take(1_000).map { "${it.line}  ${it.kind}  ${it.name}" }.toTypedArray()) { _, which ->
                        symbols.getOrNull(which)?.let { goToLine(it.line) }
                    }.setNegativeButton("Fechar", null).show()
            } else {
                val result = withContext(Dispatchers.Default) { EditorTools.structure(profile, source) }
                result.onSuccess { tree ->
                    AlertDialog.Builder(this@TextEditorActivity).setTitle("Estrutura — ${profile.label}")
                        .setMessage(tree).setPositiveButton("Fechar", null).show()
                }.onFailure { showError(it.message ?: "Falha ao gerar estrutura") }
            }
        }
    }

    private fun replaceWholeDocument(value: String, message: String) {
        if (value == editor.text.toString()) { toast("Nenhuma alteração necessária"); return }
        if (!largeFileMode && editor.length() <= HISTORY_TEXT_LIMIT) {
            undo.addLast(editor.text.toString())
            while (undo.size > MAX_HISTORY) undo.removeFirst()
        }
        loading = true
        editor.setText(value)
        editor.setSelection(value.length.coerceAtMost(editor.length()))
        previous = value
        loading = false
        redo.clear()
        if (heavyFeatures) SyntaxHighlighter.apply(editor.text, displayName)
        updateTitle()
        updateInfo()
        toast(message)
    }

    private fun allowHeavyAction(action: String): Boolean {
        if (readOnly) { showError("Não é possível $action em prévia parcial"); return false }
        if (!heavyFeatures) { showError("Large File Mode: $action foi desativado para proteger memória e desempenho"); return false }
        return true
    }

    private fun preview() {
        if (profile.language !in setOf(EditorLanguage.HTML, EditorLanguage.MARKDOWN)) return
        val open = {
            startActivity(Intent(this, HtmlPreviewActivity::class.java).putFileLocation(location, displayName))
        }
        if (isDirty()) save(open) else open()
    }

    private fun showSearch() {
        val dialog = AlertDialog.Builder(this).setTitle("Buscar e substituir").create()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(2), dp(18), dp(10))
        }
        val find = EditText(this).apply { hint = "Buscar"; setSingleLine() }
        val replacement = EditText(this).apply { hint = "Substituir por"; setSingleLine() }
        box.addView(find)
        box.addView(replacement)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(label: String, weight: Float = 1f, action: () -> Unit) {
            actions.addView(Button(this@TextEditorActivity).apply { text = label; setOnClickListener { action() } }, LinearLayout.LayoutParams(0, dp(46), weight))
        }
        add("ANTERIOR") { findPrevious(find.text.toString()) }
        add("PRÓXIMO") { findNext(find.text.toString()) }
        add("TROCAR") { replaceCurrent(find.text.toString(), replacement.text.toString()) }
        add("TODOS") { replaceAll(find.text.toString(), replacement.text.toString()) }
        box.addView(actions)
        box.addView(Button(this).apply { text = "FECHAR"; setOnClickListener { dialog.dismiss() } }, LinearLayout.LayoutParams(-1, dp(44)))
        dialog.setView(box)
        dialog.setOnShowListener { find.requestFocus() }
        dialog.show()
    }

    private fun findNext(query: String) = find(query, forward = true)
    private fun findPrevious(query: String) = find(query, forward = false)

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
        } else toast("Não encontrado")
    }

    private fun replaceCurrent(query: String, replacement: String) {
        if (query.isEmpty() || readOnly) return
        val start = editor.selectionStart
        val end = editor.selectionEnd
        if (start >= 0 && end > start && editor.text.subSequence(start, end).toString().equals(query, true)) {
            editor.text.replace(start, end, replacement)
            editor.setSelection((start + replacement.length).coerceAtMost(editor.length()))
        } else {
            findNext(query)
        }
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
        val value = undo.removeLastOrNull() ?: return
        redo.addLast(editor.text.toString())
        setEditorSnapshot(value)
    }

    private fun performRedo() {
        val value = redo.removeLastOrNull() ?: return
        undo.addLast(editor.text.toString())
        setEditorSnapshot(value)
    }

    private fun setEditorSnapshot(value: String) {
        loading = true
        editor.setText(value)
        editor.setSelection(value.length.coerceAtMost(editor.length()))
        previous = value
        loading = false
        if (heavyFeatures) SyntaxHighlighter.apply(editor.text, displayName)
        updateTitle()
        updateInfo()
    }

    private fun showOverflow(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Salvar como")
            menu.add("Ir para linha")
            menu.add(if (wordWrap) "Desativar quebra de linha" else "Ativar quebra de linha")
            menu.add("Aumentar fonte")
            menu.add("Diminuir fonte")
            menu.add("Encoding")
            menu.add("Final de linha")
            menu.add("Indentar seleção")
            menu.add("Diminuir indentação")
            menu.add("Duplicar linha")
            menu.add("Excluir linha")
            menu.add("Mover linha para cima")
            menu.add("Mover linha para baixo")
            if (profile.commentPrefix != null) {
                menu.add("Comentar")
                menu.add("Descomentar")
            }
            menu.add("Copiar")
            menu.add("Recortar")
            menu.add("Colar")
            menu.add("Selecionar tudo")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Salvar como" -> saveAs()
                    "Ir para linha" -> promptGoToLine()
                    "Ativar quebra de linha", "Desativar quebra de linha" -> { wordWrap = !wordWrap; editor.setWordWrapEnabled(wordWrap) }
                    "Aumentar fonte" -> editor.textSize = (editor.textSize / resources.displayMetrics.scaledDensity + 1f).coerceAtMost(28f)
                    "Diminuir fonte" -> editor.textSize = (editor.textSize / resources.displayMetrics.scaledDensity - 1f).coerceAtLeast(9f)
                    "Encoding" -> chooseEncoding()
                    "Final de linha" -> chooseLineEnding()
                    "Indentar seleção" -> indentSelection(false)
                    "Diminuir indentação" -> indentSelection(true)
                    "Duplicar linha" -> duplicateLine()
                    "Excluir linha" -> deleteLine()
                    "Mover linha para cima" -> moveLine(-1)
                    "Mover linha para baixo" -> moveLine(1)
                    "Comentar" -> commentSelection(false)
                    "Descomentar" -> commentSelection(true)
                    "Copiar" -> copySelection(cut = false)
                    "Recortar" -> copySelection(cut = true)
                    "Colar" -> pasteClipboard()
                    "Selecionar tudo" -> editor.selectAll()
                }
                true
            }
            show()
        }
    }

    private fun promptGoToLine() {
        val input = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; hint = "Linha"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("Ir para linha").setView(input)
            .setPositiveButton("Ir") { _, _ -> input.text.toString().toIntOrNull()?.let(::goToLine) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun goToLine(line: Int) {
        if (line <= 0) return
        var current = 1
        var index = 0
        val text = editor.text
        while (current < line && index < text.length) {
            if (text[index] == '\n') current++
            index++
        }
        editor.requestFocus()
        editor.setSelection(index.coerceAtMost(text.length))
    }

    private fun chooseEncoding() {
        val names = arrayOf("UTF-8", "UTF-16LE", "UTF-16BE")
        AlertDialog.Builder(this).setTitle("Encoding ao salvar").setItems(names) { _, which ->
            charset = when (which) { 1 -> Charsets.UTF_16LE; 2 -> Charsets.UTF_16BE; else -> Charsets.UTF_8 }
            bom = when (which) {
                1 -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
                2 -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
                else -> if (bom.contentEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))) bom else ByteArray(0)
            }
            updateInfo()
        }.show()
    }

    private fun chooseLineEnding() {
        val values = arrayOf("LF (Unix/Android)", "CRLF (Windows)", "CR (clássico)")
        AlertDialog.Builder(this).setTitle("Final de linha").setItems(values) { _, which ->
            lineEnding = when (which) { 1 -> "\r\n"; 2 -> "\r"; else -> "\n" }
            updateInfo()
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

    private fun lineBounds(position: Int = editor.selectionStart.coerceAtLeast(0)): IntRange {
        val text = editor.text
        val start = if (position <= 0) 0 else text.lastIndexOf('\n', (position - 1).coerceAtMost(text.lastIndex)).let { if (it < 0) 0 else it + 1 }
        val endIndex = text.indexOf('\n', position.coerceAtMost(text.length)).let { if (it < 0) text.length else it }
        return start until endIndex
    }

    private fun duplicateLine() {
        if (readOnly) return
        val range = lineBounds()
        val line = editor.text.subSequence(range.first, range.last + 1).toString()
        val insertAt = range.last + 1
        editor.text.insert(insertAt, "\n$line")
        editor.setSelection((insertAt + 1).coerceAtMost(editor.length()))
    }

    private fun deleteLine() {
        if (readOnly) return
        val range = lineBounds()
        var start = range.first
        var end = range.last + 1
        if (end < editor.length() && editor.text[end] == '\n') end++ else if (start > 0 && editor.text[start - 1] == '\n') start--
        editor.text.delete(start, end)
        editor.setSelection(start.coerceAtMost(editor.length()))
    }

    private fun moveLine(direction: Int) {
        if (readOnly || direction == 0) return
        val text = editor.text.toString()
        val lines = text.split('\n').toMutableList()
        val cursor = editor.selectionStart.coerceAtLeast(0)
        val current = text.take(cursor).count { it == '\n' }
        val target = current + direction
        if (current !in lines.indices || target !in lines.indices) return
        val temp = lines[current]
        lines[current] = lines[target]
        lines[target] = temp
        replaceWholeDocument(lines.joinToString("\n"), "Linha movida")
        goToLine(target + 1)
    }

    private fun indentSelection(outdent: Boolean) = transformSelectedLines { line ->
        if (outdent) when {
            line.startsWith("    ") -> line.drop(4)
            line.startsWith('\t') -> line.drop(1)
            else -> line
        } else "    $line"
    }

    private fun commentSelection(uncomment: Boolean) {
        val prefix = profile.commentPrefix ?: return
        if (prefix.contains(' ')) { toast("Comentário em bloco use a seleção nativa nesta linguagem"); return }
        transformSelectedLines { line ->
            if (uncomment) {
                val indent = line.takeWhile { it == ' ' || it == '\t' }
                val rest = line.drop(indent.length)
                if (rest.startsWith(prefix)) indent + rest.removePrefix(prefix).removePrefix(" ") else line
            } else {
                val indent = line.takeWhile { it == ' ' || it == '\t' }
                indent + prefix + " " + line.drop(indent.length)
            }
        }
    }

    private fun transformSelectedLines(transform: (String) -> String) {
        if (readOnly) return
        val text = editor.text.toString()
        val startSelection = editor.selectionStart.coerceAtLeast(0)
        val endSelection = editor.selectionEnd.coerceAtLeast(startSelection)
        val blockStart = if (startSelection == 0) 0 else text.lastIndexOf('\n', startSelection - 1).let { if (it < 0) 0 else it + 1 }
        val blockEnd = text.indexOf('\n', endSelection).let { if (it < 0) text.length else it }
        val changed = text.substring(blockStart, blockEnd).split('\n').joinToString("\n", transform = transform)
        editor.text.replace(blockStart, blockEnd, changed)
        editor.setSelection(blockStart, (blockStart + changed.length).coerceAtMost(editor.length()))
    }

    private fun copySelection(cut: Boolean) {
        val start = editor.selectionStart
        val end = editor.selectionEnd
        if (start < 0 || end <= start) return
        val value = editor.text.subSequence(start, end).toString()
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(displayName, value))
        if (cut && !readOnly) editor.text.delete(start, end)
    }

    private fun pasteClipboard() {
        if (readOnly) return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val value = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        val start = editor.selectionStart.coerceAtLeast(0)
        val end = editor.selectionEnd.coerceAtLeast(start)
        editor.text.replace(start, end, value)
    }

    private fun scheduleHighlight() {
        if (!heavyFeatures) return
        handler.removeCallbacks(highlightTask)
        handler.postDelayed(highlightTask, 120)
    }

    private fun requestClose() {
        if (isDirty()) {
            AlertDialog.Builder(this).setTitle("Alterações não salvas")
                .setMessage("Salvar as alterações em $displayName?")
                .setPositiveButton("Salvar") { _, _ -> save { finish() } }
                .setNegativeButton("Descartar") { _, _ -> finish() }
                .setNeutralButton("Cancelar", null).show()
        } else finish()
    }

    private fun isDirty(): Boolean = !loading && !readOnly && ::editor.isInitialized && editor.text.toString() != original

    private fun updateTitle() {
        if (!::title.isInitialized) return
        title.text = buildString {
            append(displayName)
            append("  •  ").append(profile.label)
            if (isDirty()) append("  •  alterado")
            if (largeFileMode) append("  •  LARGE")
            if (readOnly) append("  •  leitura")
        }
    }

    private fun updateInfo() {
        if (!::info.isInitialized || !::editor.isInitialized) return
        val pos = editor.selectionStart.coerceAtLeast(0).coerceAtMost(editor.length())
        val text = editor.text
        var line = 1
        var lastBreak = -1
        var i = 0
        while (i < pos) {
            if (text[i] == '\n') { line++; lastBreak = i }
            i++
        }
        val column = pos - lastBreak
        val lineLabel = when (lineEnding) { "\r\n" -> "CRLF"; "\r" -> "CR"; else -> "LF" }
        info.text = "Ln $line  Col $column   •   ${charset.name()}   •   $lineLabel${if (wordWrap) "   •   wrap" else ""}"
    }

    private fun showError(message: String) = AlertDialog.Builder(this).setTitle("Editor").setMessage(message).setPositiveButton("OK", null).show()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_NORMAL_EDIT_BYTES = 8L * 1024 * 1024
        private const val MAX_LARGE_EDIT_BYTES = 32L * 1024 * 1024
        private const val PREVIEW_BYTES = 4 * 1024 * 1024
        private const val HISTORY_TEXT_LIMIT = 2 * 1024 * 1024
        private const val MAX_HISTORY = 50
        private val BRACED_LANGUAGES = setOf(
            EditorLanguage.JAVA, EditorLanguage.KOTLIN, EditorLanguage.JAVASCRIPT, EditorLanguage.TYPESCRIPT,
            EditorLanguage.C, EditorLanguage.CPP, EditorLanguage.CSHARP, EditorLanguage.RUST, EditorLanguage.GO,
            EditorLanguage.PHP, EditorLanguage.SWIFT, EditorLanguage.DART, EditorLanguage.CSS
        )
    }
}

private fun <T> ArrayDeque<T>.removeLastOrNull(): T? = if (isEmpty()) null else removeLast()
