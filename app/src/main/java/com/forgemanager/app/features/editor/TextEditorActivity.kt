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
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * MT-style text/code editor.
 *
 * Small files are fully editable with syntax highlighting and undo/redo snapshots.
 * Large files never get placed wholesale into EditText: they are opened in a
 * paged, read-only viewer so multi-megabyte TXT/log files do not exhaust the UI
 * thread or heap while Android builds a gigantic Layout/Spannable.
 */
class TextEditorActivity : ForgeActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var editor: LineNumberEditText
    private lateinit var title: TextView
    private lateinit var cursorPosition: TextView
    private lateinit var info: TextView
    private lateinit var location: FileLocation
    private lateinit var displayName: String
    private lateinit var profile: EditorProfile
    private lateinit var profileActions: LinearLayout
    private lateinit var symbolBar: LinearLayout

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

    private var pageOffset = 0L
    private var pageBytesLoaded = 0

    private var historyCurrent = ""
    private val undo = ArrayDeque<String>()
    private val redo = ArrayDeque<String>()
    private var pendingHighlightStart = Int.MAX_VALUE
    private var pendingHighlightEnd = 0

    private val historyTask = Runnable { commitHistorySnapshot() }
    private val highlightTask = Runnable {
        if (!::editor.isInitialized || loading || !heavyFeatures || pagedMode) return@Runnable
        val start = editor.selectionStart.coerceAtLeast(0)
        val end = editor.selectionEnd.coerceAtLeast(0)
        val from = pendingHighlightStart.takeIf { it != Int.MAX_VALUE } ?: start
        val to = pendingHighlightEnd.coerceAtLeast(from + 1)
        pendingHighlightStart = Int.MAX_VALUE
        pendingHighlightEnd = 0
        SyntaxHighlighter.applyChanged(editor.text, displayName, from, to)
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
            setBackgroundColor(UiPreferences.background(this@TextEditorActivity))
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), 0)
            setBackgroundColor(Color.rgb(43, 43, 43))
        }
        toolbar.addView(toolbarButton("☰", "Menu") { showMainMenu(it) })
        toolbar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        toolbar.addView(toolbarButton("◆", "Manter tela ligada") { toggleKeepScreenOn() })
        toolbar.addView(toolbarButton("↶", "Desfazer") { performUndo() })
        toolbar.addView(toolbarButton("↷", "Refazer") { performRedo() })
        toolbar.addView(toolbarButton("▣", "Salvar") { save() })
        toolbar.addView(toolbarButton("✎", "Editar") { focusEditor() })
        toolbar.addView(toolbarButton("⋮", "Mais") { showOverflow(it) })
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(54)))

        val tab = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            setBackgroundColor(Color.rgb(31, 31, 31))
        }
        title = TextView(this).apply {
            text = displayName
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            textSize = 11f
            setTextColor(Color.rgb(215, 215, 215))
        }
        cursorPosition = TextView(this).apply {
            text = "1:1"
            textSize = 10.5f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTextColor(Color.rgb(190, 190, 190))
        }
        tab.addView(title, LinearLayout.LayoutParams(0, -1, 1f))
        tab.addView(cursorPosition, LinearLayout.LayoutParams(dp(70), -1))
        root.addView(tab, LinearLayout.LayoutParams(-1, dp(28)))

        profileActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            setBackgroundColor(Color.rgb(52, 52, 52))
        }
        val actionScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(profileActions)
        }
        root.addView(actionScroll, LinearLayout.LayoutParams(-1, dp(38)))
        populateProfileActions()

        editor = LineNumberEditText(this).apply {
            applyPalette(
                UiPreferences.surface(this@TextEditorActivity),
                UiPreferences.textPrimary(this@TextEditorActivity),
                UiPreferences.textSecondary(this@TextEditorActivity),
                UiPreferences.divider(this@TextEditorActivity)
            )
            setSelectAllOnFocus(false)
            setWordWrapEnabled(wordWrap)
            onSelectionChangedListener = { _, _ -> updateCursorInfo() }
        }
        editor.addTextChangedListener(editorWatcher())
        root.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))

        symbolBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), 0)
            setBackgroundColor(UiPreferences.elevatedSurface(this@TextEditorActivity))
        }
        val symbolScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(symbolBar)
        }
        root.addView(symbolScroll, LinearLayout.LayoutParams(-1, dp(42)))
        populateSymbolBar()

        info = TextView(this).apply {
            textSize = 9.5f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            setTextColor(UiPreferences.textSecondary(this@TextEditorActivity))
            setBackgroundColor(UiPreferences.surface(this@TextEditorActivity))
        }
        root.addView(info, LinearLayout.LayoutParams(-1, dp(24)))
        return root
    }

    private fun toolbarButton(label: String, description: String, action: (View) -> Unit) = Button(this).apply {
        text = label
        contentDescription = description
        textSize = if (label.length == 1) 18f else 14f
        minWidth = dp(43)
        minimumWidth = dp(43)
        minHeight = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(2), 0, dp(2), 0)
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener(action)
    }

    private fun smallAction(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 8.8f
        minWidth = dp(54)
        minimumWidth = dp(54)
        setPadding(dp(7), 0, dp(7), 0)
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { action() }
    }

    private fun populateProfileActions() {
        if (!::profileActions.isInitialized) return
        profileActions.removeAllViews()
        profileActions.addView(TextView(this).apply {
            text = profile.label.uppercase()
            textSize = 8.8f
            setTextColor(Color.rgb(90, 190, 255))
            setPadding(dp(7), 0, dp(8), 0)
        })

        if (pagedMode) {
            profileActions.addView(smallAction("◀ BLOCO") { previousPage() })
            profileActions.addView(smallAction("BLOCO ▶") { nextPage() })
            profileActions.addView(TextView(this).apply {
                text = pageLabel()
                textSize = 8.5f
                setTextColor(Color.LTGRAY)
                setPadding(dp(8), 0, dp(8), 0)
            })
            return
        }

        if (profile.canFormat) profileActions.addView(smallAction("FORMATAR") { formatDocument() })
        if (profile.canValidate) profileActions.addView(smallAction("VALIDAR") { validateDocument() })
        if (profile.canOutline) profileActions.addView(smallAction("SÍMBOLOS") { showStructure() })
        if (profile.canPreview) profileActions.addView(smallAction("PREVIEW") { preview() })
        if (profile.language in setOf(EditorLanguage.XML, EditorLanguage.JSON)) {
            profileActions.addView(smallAction("MINIFICAR") { minifyDocument() })
        }
    }

    private fun populateSymbolBar() {
        if (!::symbolBar.isInitialized) return
        symbolBar.removeAllViews()
        for (symbol in profile.symbolBar) {
            symbolBar.addView(Button(this).apply {
                text = symbol
                textSize = 10.5f
                minWidth = dp(40)
                minimumWidth = dp(40)
                setPadding(dp(6), 0, dp(6), 0)
                setTextColor(UiPreferences.textPrimary(this@TextEditorActivity))
                setBackgroundColor(Color.TRANSPARENT)
                isEnabled = !readOnly
                alpha = if (readOnly) 0.45f else 1f
                setOnClickListener { insertSymbol(symbol) }
            })
        }
    }

    private fun editorWatcher() = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            pendingHighlightStart = minOf(pendingHighlightStart, start)
            pendingHighlightEnd = maxOf(pendingHighlightEnd, start + count)
        }

        override fun afterTextChanged(s: Editable?) {
            if (s == null || loading || readOnly) return
            dirty = true
            scheduleHistorySnapshot()
            scheduleHighlight()
            updateTitle()
            updateCursorInfo()
        }
    }

    private fun scheduleHistorySnapshot() {
        if (!historyEnabled || loading) return
        handler.removeCallbacks(historyTask)
        handler.postDelayed(historyTask, HISTORY_DEBOUNCE_MS)
    }

    private fun commitHistorySnapshot() {
        if (!historyEnabled || loading || !::editor.isInitialized) return
        val now = editor.text.toString()
        if (now == historyCurrent) return
        undo.addLast(historyCurrent)
        while (undo.size > MAX_HISTORY) undo.removeFirst()
        historyCurrent = now
        redo.clear()
    }

    private fun scheduleHighlight() {
        if (!heavyFeatures || pagedMode) return
        handler.removeCallbacks(highlightTask)
        handler.postDelayed(highlightTask, HIGHLIGHT_DEBOUNCE_MS)
    }

    private fun loadInitial() {
        title.text = "Abrindo $displayName…"
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
                        val bytes = backend.openInput(location).use { input ->
                            readAtMost(input, MAX_EDITABLE_BYTES.toInt() + 1)
                        }
                        if (bytes.size > MAX_EDITABLE_BYTES) error("Arquivo excedeu o limite de edição segura")
                        detectEncoding(bytes)
                    }
                }
            }
            result.onSuccess { text -> applyLoadedText(text, initial = true) }
                .onFailure { showError(it.message ?: "Falha ao abrir arquivo") }
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
        if (heavyFeatures) SyntaxHighlighter.apply(editor.text, displayName)
        populateProfileActions()
        populateSymbolBar()
        updateTitle()
        updateCursorInfo()

        if (initial && pagedMode) {
            Toast.makeText(
                this,
                "Arquivo grande: aberto em blocos para evitar travamentos. Edição total foi desativada para proteger memória.",
                Toast.LENGTH_LONG
            ).show()
        } else if (initial && !heavyFeatures && !pagedMode) {
            Toast.makeText(this, "Modo leve: highlight e análise pesada foram desativados para este arquivo.", Toast.LENGTH_LONG).show()
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
        title.text = "Carregando bloco…"
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val target = offset.coerceIn(0L, fileSize.coerceAtLeast(0L))
                    val bytes = readPageBytes(target)
                    target to bytes
                }
            }
            result.onSuccess { (target, bytes) ->
                pageOffset = target
                pageBytesLoaded = bytes.size
                applyLoadedText(decodeChunk(bytes, firstPage = target == 0L), initial = false)
            }.onFailure { showError(it.message ?: "Falha ao carregar bloco") }
        }
    }

    private fun pageLabel(): String {
        if (!pagedMode || fileSize <= 0L) return ""
        val start = pageOffset + 1
        val end = (pageOffset + pageBytesLoaded).coerceAtMost(fileSize)
        return "${formatBytes(start)}–${formatBytes(end)} / ${formatBytes(fileSize)}"
    }

    private fun save(after: (() -> Unit)? = null) {
        if (pagedMode || readOnly) {
            showError("Arquivos grandes são abertos em blocos somente leitura. Isso evita travamentos e impede sobrescrever o arquivo com apenas um trecho.")
            return
        }
        handler.removeCallbacks(historyTask)
        val content = editor.text.toString()
        title.text = "Salvando $displayName…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { writeLocation(location, content) } }
                .onSuccess {
                    dirty = false
                    historyCurrent = if (historyEnabled) content else ""
                    fileSize = bom.size + encodedText(content).size.toLong()
                    updateTitle()
                    updateCursorInfo()
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
        if (readOnly || pagedMode) {
            showError("Salvar como fica desativado no modo de arquivo grande por blocos.")
            return
        }
        val input = EditText(this).apply {
            setText(displayName)
            setSelection(text.length)
            setSingleLine()
        }
        AlertDialog.Builder(this).setTitle("Salvar como").setView(input)
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
                        profile = EditorProfile.forFile(displayName)
                        dirty = false
                        populateProfileActions()
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

    private fun minifyDocument() {
        if (!allowHeavyAction("minificar")) return
        val source = editor.text.toString()
        scope.launch {
            val result = withContext(Dispatchers.Default) { EditorTools.minify(profile, source) }
            result.onSuccess { replaceWholeDocument(it, "Documento minificado") }
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
                AlertDialog.Builder(this@TextEditorActivity)
                    .setTitle("Símbolos — ${profile.label}")
                    .setItems(symbols.take(1_000).map { "${it.line}  ${it.kind}  ${it.name}" }.toTypedArray()) { _, which ->
                        symbols.getOrNull(which)?.let { goToLine(it.line) }
                    }
                    .setNegativeButton("Fechar", null)
                    .show()
            } else {
                val result = withContext(Dispatchers.Default) { EditorTools.structure(profile, source) }
                result.onSuccess {
                    AlertDialog.Builder(this@TextEditorActivity)
                        .setTitle("Estrutura — ${profile.label}")
                        .setMessage(it)
                        .setPositiveButton("Fechar", null)
                        .show()
                }.onFailure { showError(it.message ?: "Falha ao gerar estrutura") }
            }
        }
    }

    private fun allowHeavyAction(action: String): Boolean {
        if (pagedMode || readOnly) {
            showError("Não é possível $action no modo de arquivo grande por blocos.")
            return false
        }
        if (!heavyFeatures) {
            showError("Modo leve: $action foi desativado para proteger memória e desempenho.")
            return false
        }
        return true
    }

    private fun preview() {
        if (profile.language !in setOf(EditorLanguage.HTML, EditorLanguage.MARKDOWN) || pagedMode) return
        val open = { startActivity(Intent(this, HtmlPreviewActivity::class.java).putFileLocation(location, displayName)) }
        if (dirty) save(open) else open()
    }

    private fun replaceWholeDocument(value: String, message: String) {
        if (readOnly || pagedMode) return
        if (value == editor.text.toString()) {
            toast("Nenhuma alteração necessária")
            return
        }
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
        if (heavyFeatures) SyntaxHighlighter.apply(editor.text, displayName)
        updateTitle()
        updateCursorInfo()
        toast(message)
    }

    private fun showSearch() {
        val dialog = AlertDialog.Builder(this).setTitle(if (pagedMode) "Buscar no bloco" else "Buscar e substituir").create()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(2), dp(18), dp(10))
        }
        val find = EditText(this).apply { hint = "Buscar"; setSingleLine() }
        val replacement = EditText(this).apply { hint = "Substituir por"; setSingleLine(); isEnabled = !readOnly }
        box.addView(find)
        box.addView(replacement)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(label: String, enabled: Boolean = true, action: () -> Unit) {
            actions.addView(Button(this@TextEditorActivity).apply {
                text = label
                isEnabled = enabled
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        add("ANT.") { findPrevious(find.text.toString()) }
        add("PRÓX.") { findNext(find.text.toString()) }
        add("TROCAR", !readOnly) { replaceCurrent(find.text.toString(), replacement.text.toString()) }
        add("TODOS", !readOnly) { replaceAll(find.text.toString(), replacement.text.toString()) }
        box.addView(actions)
        box.addView(Button(this).apply { text = "FECHAR"; setOnClickListener { dialog.dismiss() } }, LinearLayout.LayoutParams(-1, dp(42)))
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
            text.lastIndexOf(query, from, ignoreCase = true).let {
                if (it < 0) text.lastIndexOf(query, text.lastIndex, true) else it
            }
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
        if (!historyEnabled || readOnly) {
            if (!readOnly) toast("Undo desativado neste arquivo para economizar memória")
            return
        }
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
        if (heavyFeatures) SyntaxHighlighter.apply(editor.text, displayName)
        updateTitle()
        updateCursorInfo()
    }

    private fun showMainMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Buscar")
            if (!readOnly) menu.add("Salvar")
            if (!readOnly) menu.add("Salvar como")
            menu.add("Ir para linha")
            if (pagedMode) {
                menu.add("Bloco anterior")
                menu.add("Próximo bloco")
            } else {
                if (profile.canFormat) menu.add("Formatar")
                if (profile.canValidate) menu.add("Validar")
                if (profile.canOutline) menu.add("Símbolos")
                if (profile.canPreview) menu.add("Preview")
            }
            menu.add("Fechar")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Buscar" -> showSearch()
                    "Salvar" -> save()
                    "Salvar como" -> saveAs()
                    "Ir para linha" -> promptGoToLine()
                    "Bloco anterior" -> previousPage()
                    "Próximo bloco" -> nextPage()
                    "Formatar" -> formatDocument()
                    "Validar" -> validateDocument()
                    "Símbolos" -> showStructure()
                    "Preview" -> preview()
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
                        updateCursorInfo()
                    }
                    "Aumentar fonte" -> editor.setEditorZoomSp(editor.editorZoomSp() + 1f)
                    "Diminuir fonte" -> editor.setEditorZoomSp(editor.editorZoomSp() - 1f)
                    "Encoding" -> chooseEncoding()
                    "Final de linha" -> chooseLineEnding()
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
        if (layout != null) {
            val index = (line - 1).coerceIn(0, (layout.lineCount - 1).coerceAtLeast(0))
            val offset = layout.getLineStart(index).coerceIn(0, editor.length())
            editor.requestFocus()
            editor.setSelection(offset)
            editor.bringPointIntoView(offset)
            return
        }
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
            charset = when (which) {
                1 -> Charsets.UTF_16LE
                2 -> Charsets.UTF_16BE
                else -> Charsets.UTF_8
            }
            bom = when (which) {
                1 -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
                2 -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
                else -> if (bom.contentEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))) bom else ByteArray(0)
            }
            dirty = true
            updateCursorInfo()
        }.show()
    }

    private fun chooseLineEnding() {
        val values = arrayOf("LF (Unix/Android)", "CRLF (Windows)", "CR (clássico)")
        AlertDialog.Builder(this).setTitle("Final de linha").setItems(values) { _, which ->
            lineEnding = when (which) {
                1 -> "\r\n"
                2 -> "\r"
                else -> "\n"
            }
            dirty = true
            updateCursorInfo()
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
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Forge Manager", value))
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
            toast("Arquivo grande aberto em modo somente leitura por blocos")
            return
        }
        editor.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun toggleKeepScreenOn() {
        keepScreenOn = !keepScreenOn
        if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        toast(if (keepScreenOn) "Tela mantida ligada" else "Modo fixo desativado")
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
        title.text = buildString {
            append(displayName)
            if (dirty) append(" *")
            if (pagedMode) append("  [LARGE]")
        }
    }

    private fun updateCursorInfo() {
        if (!::editor.isInitialized || !::cursorPosition.isInitialized || !::info.isInitialized) return
        val pos = editor.selectionStart.coerceIn(0, editor.length())
        val currentLayout = editor.layout
        val lineIndex = if (currentLayout != null && currentLayout.lineCount > 0) {
            currentLayout.getLineForOffset(pos.coerceAtMost(editor.length()))
        } else {
            var count = 0
            var i = 0
            val text = editor.text
            while (i < pos) {
                if (text[i] == '\n') count++
                i++
            }
            count
        }
        val lineStart = if (currentLayout != null && lineIndex < currentLayout.lineCount) {
            currentLayout.getLineStart(lineIndex)
        } else {
            editor.text.lastIndexOf('\n', (pos - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        }
        val column = (pos - lineStart + 1).coerceAtLeast(1)
        cursorPosition.text = "${lineIndex + 1}:$column"
        val eol = when (lineEnding) { "\r\n" -> "CRLF"; "\r" -> "CR"; else -> "LF" }
        info.text = buildString {
            append("Ln ").append(lineIndex + 1).append("  Col ").append(column)
            append("   •   ").append(charset.name()).append("   •   ").append(eol)
            if (wordWrap) append("   •   wrap")
            if (pagedMode) append("   •   ").append(pageLabel())
            else append("   •   ").append(formatBytes(fileSize))
        }
    }

    private fun formatBytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var size = value.toDouble()
        var index = -1
        do {
            size /= 1024.0
            index++
        } while (size >= 1024.0 && index < units.lastIndex)
        return String.format(java.util.Locale.US, "%.1f %s", size, units[index])
    }

    private fun showError(message: String) = AlertDialog.Builder(this)
        .setTitle("Editor")
        .setMessage(message)
        .setPositiveButton("OK", null)
        .show()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_EDITABLE_BYTES = 2 * 1024 * 1024
        private const val PAGE_BYTES = 384 * 1024
        private const val HIGHLIGHT_BYTES = 512 * 1024L
        private const val HISTORY_BYTES = 512 * 1024L
        private const val MAX_HISTORY = 18
        private const val HISTORY_DEBOUNCE_MS = 450L
        private const val HIGHLIGHT_DEBOUNCE_MS = 220L
    }
}

private fun <T> ArrayDeque<T>.removeLastOrNull(): T? = if (isEmpty()) null else removeLast()
