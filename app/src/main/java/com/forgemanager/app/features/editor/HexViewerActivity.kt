package com.forgemanager.app.features.editor

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.forgemanager.app.ForgeApplication
import com.forgemanager.app.core.file.FileLocation
import com.forgemanager.app.core.file.fileDisplayName
import com.forgemanager.app.core.file.readFileLocation
import com.forgemanager.app.core.ui.ForgeActivity
import com.forgemanager.app.features.explorer.FileListAdapter
import com.forgemanager.app.features.settings.UiPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.TreeMap

class HexViewerActivity : ForgeActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }
    private lateinit var location: FileLocation
    private lateinit var displayName: String
    private lateinit var content: TextView
    private lateinit var title: TextView
    private lateinit var pageInfo: TextView

    private var offset = 0L
    private var size = 0L
    private var selectionOffset = -1L
    private var selectionLength = 0
    private val pending = TreeMap<Long, Byte>()
    private val undo = ArrayDeque<HexEdit>()
    private val redo = ArrayDeque<HexEdit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        location = intent.readFileLocation() ?: run { finish(); return }
        displayName = intent.fileDisplayName() ?: location.displayPath.substringAfterLast('/').substringAfterLast("!/").ifBlank { "arquivo" }
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

    @Deprecated("Android back compatibility")
    override fun onBackPressed() {
        if (pending.isEmpty()) finish() else AlertDialog.Builder(this)
            .setTitle("Alterações não salvas")
            .setMessage("Salvar as alterações hexadecimais?")
            .setPositiveButton("Salvar") { _, _ -> save { finish() } }
            .setNegativeButton("Descartar") { _, _ -> finish() }
            .setNeutralButton("Cancelar", null).show()
    }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(UiPreferences.background(this@HexViewerActivity))
        val top = LinearLayout(this@HexViewerActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(UiPreferences.surface(this@HexViewerActivity))
        }
        top.addView(tool("←", "Voltar") { onBackPressed() })
        title = TextView(this@HexViewerActivity).apply {
            setTextColor(UiPreferences.textPrimary(this@HexViewerActivity))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            textSize = 12f
            text = displayName
        }
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(tool("✓", "Salvar") { save() })
        top.addView(tool("↶", "Desfazer") { performUndo() })
        top.addView(tool("↷", "Refazer") { performRedo() })
        top.addView(tool("⌕", "Buscar") { showSearch() })
        top.addView(tool("⋮", "Mais") { showMore(it) })
        addView(top, LinearLayout.LayoutParams(-1, dp(52)))

        val nav = LinearLayout(this@HexViewerActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
            setBackgroundColor(UiPreferences.elevatedSurface(this@HexViewerActivity))
        }
        nav.addView(small("‹ PÁGINA") { offset = (offset - PAGE).coerceAtLeast(0); render() })
        pageInfo = TextView(this@HexViewerActivity).apply {
            textSize = 10.5f
            gravity = Gravity.CENTER
            setTextColor(UiPreferences.textSecondary(this@HexViewerActivity))
        }
        nav.addView(pageInfo, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(small("PÁGINA ›") {
            if (size > 0) offset = (offset + PAGE).coerceAtMost(((size - 1) / PAGE) * PAGE)
            render()
        })
        addView(nav, LinearLayout.LayoutParams(-1, dp(40)))

        content = TextView(this@HexViewerActivity).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(11.5f)
            setTextColor(UiPreferences.textPrimary(this@HexViewerActivity))
            setPadding(dp(9), dp(8), dp(9), dp(12))
            setTextIsSelectable(true)
            setBackgroundColor(UiPreferences.background(this@HexViewerActivity))
        }
        addView(ScrollView(this@HexViewerActivity).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun tool(label: String, description: String, action: () -> Unit) = Button(this).apply {
        text = label
        contentDescription = description
        textSize = 15f
        minWidth = dp(40)
        minimumWidth = dp(40)
        setPadding(dp(3), 0, dp(3), 0)
        setTextColor(UiPreferences.textPrimary(this@HexViewerActivity))
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setOnClickListener { action() }
    }

    private fun small(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 9f
        setTextColor(UiPreferences.textPrimary(this@HexViewerActivity))
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setOnClickListener { action() }
    }

    private fun render() {
        title.text = "$displayName${if (pending.isNotEmpty()) "  •  ${pending.size} byte(s) alterado(s)" else ""}"
        pageInfo.text = "lendo 0x${offset.toString(16).uppercase()}"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { readCurrentRange(offset, PAGE) } }
                .onSuccess { bytes ->
                    content.text = formatPage(bytes)
                    val selected = if (selectionOffset >= 0) "  •  seleção 0x${selectionOffset.toString(16).uppercase()} +$selectionLength" else ""
                    pageInfo.text = "0x${offset.toString(16).uppercase()} / ${FileListAdapter.formatBytes(size)}$selected"
                }.onFailure { showError(it.message ?: "Falha ao ler") }
        }
    }

    private fun formatPage(bytes: ByteArray): String = buildString {
        append("OFFSET    HEX                                              ASCII\n")
        for (lineStart in bytes.indices step BYTES_PER_LINE) {
            append(String.format("%08X  ", offset + lineStart))
            for (i in 0 until BYTES_PER_LINE) {
                append(if (lineStart + i < bytes.size) String.format("%02X ", bytes[lineStart + i].toInt() and 0xff) else "   ")
                if (i == 7) append(' ')
            }
            append(" ")
            for (i in 0 until BYTES_PER_LINE) if (lineStart + i < bytes.size) {
                val c = bytes[lineStart + i].toInt() and 0xff
                append(if (c in 32..126) c.toChar() else '.')
            }
            append('\n')
        }
    }

    private suspend fun readCurrentRange(start: Long, length: Int): ByteArray {
        if (start >= size || length <= 0) return ByteArray(0)
        val count = minOf(length.toLong(), size - start).toInt()
        val data = readOriginalRange(start, count)
        val end = start + data.size
        pending.subMap(start, true, end, false).forEach { (position, value) ->
            data[(position - start).toInt()] = value
        }
        return data
    }

    private suspend fun readOriginalRange(start: Long, length: Int): ByteArray {
        val direct = directFile()
        if (direct != null && direct.canRead()) {
            val data = ByteArray(length)
            val count = RandomAccessFile(direct, "r").use { file ->
                file.seek(start.coerceAtMost(file.length()))
                file.read(data)
            }
            return if (count <= 0) ByteArray(0) else data.copyOf(count)
        }
        val backend = graph.resolver.backendFor(location)
        return backend.openInput(location).use { input ->
            skipFully(input, start)
            val data = ByteArray(length)
            var filled = 0
            while (filled < data.size) {
                val n = input.read(data, filled, data.size - filled)
                if (n < 0) break
                filled += n
            }
            if (filled == data.size) data else data.copyOf(filled)
        }
    }

    private fun showMore(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Ir para offset")
            menu.add("Selecionar range")
            menu.add("Editar HEX")
            menu.add("Editar ASCII")
            menu.add("Buscar HEX")
            menu.add("Buscar texto")
            menu.add("Substituir HEX")
            menu.add("Copiar seleção")
            menu.add("Copiar seleção como HEX")
            menu.add("Copiar página como HEX")
            menu.add("Salvar como")
            menu.add("Propriedades")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Ir para offset" -> goToOffset()
                    "Selecionar range" -> selectRange()
                    "Editar HEX" -> editBytes(ascii = false)
                    "Editar ASCII" -> editBytes(ascii = true)
                    "Buscar HEX" -> promptSearch(hex = true)
                    "Buscar texto" -> promptSearch(hex = false)
                    "Substituir HEX" -> promptReplaceHex()
                    "Copiar seleção" -> copySelected(asHex = false)
                    "Copiar seleção como HEX" -> copySelected(asHex = true)
                    "Copiar página como HEX" -> copyPageHex()
                    "Salvar como" -> saveAs()
                    "Propriedades" -> showProperties()
                }
                true
            }
            show()
        }
    }

    private fun goToOffset() {
        val input = EditText(this).apply { hint = "Offset (decimal ou 0xHEX)"; inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("Ir para offset").setView(input).setPositiveButton("Ir") { _, _ ->
            val value = parseOffset(input.text.toString())
            if (value != null && value in 0..size) {
                offset = (value / PAGE) * PAGE
                selectionOffset = value.takeIf { it < size } ?: -1
                selectionLength = if (value < size) 1 else 0
                render()
            } else toast("Offset inválido")
        }.setNegativeButton("Cancelar", null).show()
    }

    private fun selectRange() {
        val box = twoInputs("Offset", "Quantidade de bytes")
        AlertDialog.Builder(this).setTitle("Selecionar range").setView(box.first)
            .setPositiveButton("Selecionar") { _, _ ->
                val start = parseOffset(box.second.first.text.toString())
                val length = box.second.second.text.toString().toIntOrNull()
                if (start == null || length == null || length <= 0 || start !in 0 until size || start + length > size) {
                    toast("Range inválido")
                } else {
                    selectionOffset = start
                    selectionLength = length.coerceAtMost(MAX_SELECTION_BYTES)
                    offset = (start / PAGE) * PAGE
                    render()
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun editBytes(ascii: Boolean) {
        if (size <= 0) return
        val box = twoInputs("Offset", if (ascii) "Texto ASCII/UTF-8" else "Bytes HEX: DE AD BE EF")
        box.second.first.setText((selectionOffset.takeIf { it >= 0 } ?: offset).toString())
        AlertDialog.Builder(this).setTitle(if (ascii) "Editar ASCII" else "Editar HEX").setView(box.first)
            .setPositiveButton("Aplicar") { _, _ ->
                val position = parseOffset(box.second.first.text.toString())
                val bytes = if (ascii) box.second.second.text.toString().toByteArray(Charsets.UTF_8) else parseHex(box.second.second.text.toString())
                if (position == null || bytes == null || bytes.isEmpty() || position !in 0 until size || position + bytes.size > size) {
                    toast("Dados ou offset inválidos")
                } else stageEdit(position, bytes)
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun showSearch() = AlertDialog.Builder(this).setTitle("Buscar")
        .setItems(arrayOf("Texto", "Bytes HEX")) { _, which -> promptSearch(hex = which == 1) }.show()

    private fun promptSearch(hex: Boolean) {
        val input = EditText(this).apply { hint = if (hex) "DE AD BE EF" else "Texto"; setSingleLine() }
        AlertDialog.Builder(this).setTitle(if (hex) "Buscar HEX" else "Buscar texto").setView(input)
            .setPositiveButton("Buscar") { _, _ ->
                val pattern = if (hex) parseHex(input.text.toString()) else input.text.toString().toByteArray(Charsets.UTF_8)
                if (pattern == null || pattern.isEmpty()) { toast("Busca inválida"); return@setPositiveButton }
                runSearch(pattern)
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun runSearch(pattern: ByteArray, from: Long = (selectionOffset.takeIf { it >= 0 }?.plus(1) ?: offset)) {
        title.text = "Buscando em $displayName…"
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                searchFrom(pattern, from).let { if (it < 0 && from > 0) searchFrom(pattern, 0) else it }
            } }
            result.onSuccess { found ->
                if (found >= 0) {
                    selectionOffset = found
                    selectionLength = pattern.size.coerceAtMost(MAX_SELECTION_BYTES)
                    offset = (found / PAGE) * PAGE
                    render()
                    toast("Encontrado em 0x${found.toString(16).uppercase()}")
                } else { render(); toast("Não encontrado") }
            }.onFailure { render(); showError(it.message ?: "Falha na busca") }
        }
    }

    private suspend fun searchFrom(pattern: ByteArray, start: Long): Long {
        if (pattern.isEmpty() || pattern.size > MAX_SEARCH_PATTERN || start >= size) return -1
        val bufferSize = SEARCH_BUFFER.coerceAtLeast(pattern.size * 2)
        val overlap = (pattern.size - 1).coerceAtLeast(0)
        val buffer = ByteArray(bufferSize + overlap)
        var carry = 0
        var absolute = start
        val direct = directFile()
        if (direct != null && direct.canRead()) {
            RandomAccessFile(direct, "r").use { file ->
                file.seek(start)
                while (true) {
                    val read = file.read(buffer, carry, bufferSize)
                    if (read <= 0) break
                    val total = carry + read
                    applyPending(buffer, absolute - carry, total)
                    val index = indexOf(buffer, total, pattern)
                    if (index >= 0) return absolute - carry + index
                    carry = minOf(overlap, total)
                    if (carry > 0) System.arraycopy(buffer, total - carry, buffer, 0, carry)
                    absolute += read
                }
            }
        } else {
            graph.resolver.backendFor(location).openInput(location).use { input ->
                skipFully(input, start)
                while (true) {
                    val read = input.read(buffer, carry, bufferSize)
                    if (read <= 0) break
                    val total = carry + read
                    applyPending(buffer, absolute - carry, total)
                    val index = indexOf(buffer, total, pattern)
                    if (index >= 0) return absolute - carry + index
                    carry = minOf(overlap, total)
                    if (carry > 0) System.arraycopy(buffer, total - carry, buffer, 0, carry)
                    absolute += read
                }
            }
        }
        return -1
    }

    private fun promptReplaceHex() {
        val box = twoInputs("Buscar HEX", "Substituir HEX (mesmo tamanho)")
        AlertDialog.Builder(this).setTitle("Substituir HEX").setView(box.first)
            .setPositiveButton("Buscar e substituir") { _, _ ->
                val find = parseHex(box.second.first.text.toString())
                val replace = parseHex(box.second.second.text.toString())
                if (find == null || replace == null || find.isEmpty() || !find.contentEqualsLength(replace)) {
                    toast("Os padrões devem ter o mesmo tamanho")
                    return@setPositiveButton
                }
                scope.launch {
                    val found = runCatching { withContext(Dispatchers.IO) { searchFrom(find, offset) } }.getOrElse {
                        showError(it.message ?: "Falha na busca"); return@launch
                    }
                    if (found < 0) toast("Não encontrado") else stageEdit(found, replace)
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun stageEdit(position: Long, bytes: ByteArray) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { readCurrentRange(position, bytes.size) } }
                .onSuccess { before ->
                    if (before.size != bytes.size) { toast("Range fora do arquivo"); return@onSuccess }
                    val edit = HexEdit(position, before, bytes.copyOf())
                    applyEdit(edit.after, edit.offset)
                    undo.addLast(edit)
                    while (undo.size > MAX_HISTORY) undo.removeFirst()
                    redo.clear()
                    selectionOffset = position
                    selectionLength = bytes.size.coerceAtMost(MAX_SELECTION_BYTES)
                    offset = (position / PAGE) * PAGE
                    render()
                }.onFailure { showError(it.message ?: "Falha ao preparar edição") }
        }
    }

    private fun performUndo() {
        val edit = undo.removeLastOrNull() ?: return
        applyEdit(edit.before, edit.offset)
        redo.addLast(edit)
        render()
    }

    private fun performRedo() {
        val edit = redo.removeLastOrNull() ?: return
        applyEdit(edit.after, edit.offset)
        undo.addLast(edit)
        render()
    }

    private fun applyEdit(bytes: ByteArray, position: Long) {
        bytes.forEachIndexed { index, byte -> pending[position + index] = byte }
    }

    private fun save(after: (() -> Unit)? = null) {
        if (pending.isEmpty()) { after?.invoke(); return }
        title.text = "Salvando $displayName…"
        val snapshot = TreeMap(pending)
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { writePending(snapshot) } }
                .onSuccess {
                    pending.clear(); undo.clear(); redo.clear(); render(); toast("Salvo"); after?.invoke()
                }.onFailure { render(); showError(it.message ?: "Falha ao salvar") }
        }
    }

    private suspend fun writePending(edits: Map<Long, Byte>) {
        val direct = directFile()
        if (direct != null && direct.canWrite()) {
            RandomAccessFile(direct, "rw").use { file ->
                var runStart = -1L
                val run = ArrayList<Byte>()
                fun flushRun() {
                    if (runStart < 0 || run.isEmpty()) return
                    file.seek(runStart)
                    file.write(run.toByteArray())
                    run.clear(); runStart = -1
                }
                for ((position, byte) in edits) {
                    if (runStart < 0) { runStart = position; run += byte }
                    else if (position == runStart + run.size) run += byte
                    else { flushRun(); runStart = position; run += byte }
                }
                flushRun()
                file.fd.sync()
            }
            return
        }

        val tempDir = File(cacheDir, "hex-save").apply { mkdirs() }
        require(tempDir.usableSpace > size + MIN_FREE_SPACE) { "Espaço temporário insuficiente para salvamento seguro" }
        val temp = File(tempDir, "${System.nanoTime()}.bin")
        try {
            graph.resolver.backendFor(location).openInput(location).use { input ->
                temp.outputStream().buffered(128 * 1024).use { output -> input.copyTo(output, 128 * 1024) }
            }
            RandomAccessFile(temp, "rw").use { file ->
                for ((position, byte) in edits) { file.seek(position); file.write(byte.toInt() and 0xff) }
                file.fd.sync()
            }
            graph.resolver.backendFor(location, write = true).openOutput(location, truncate = true).use { output ->
                temp.inputStream().buffered(128 * 1024).use { input -> input.copyTo(output, 128 * 1024) }
                output.flush()
            }
        } finally {
            temp.delete()
        }
    }

    private fun saveAs() {
        val input = EditText(this).apply { setText(displayName); setSelection(text.length); setSingleLine() }
        AlertDialog.Builder(this).setTitle("Salvar como").setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isBlank() || '/' in newName || '\\' in newName) { toast("Nome inválido"); return@setPositiveButton }
                val edits = TreeMap(pending)
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) {
                        val sourceBackend = graph.resolver.backendFor(location)
                        val parent = sourceBackend.parent(location) ?: error("Pasta pai indisponível")
                        val destination = sourceBackend.create(parent, newName)
                        try {
                            graph.resolver.backendFor(destination.location, write = true).openOutput(destination.location, true).use { output ->
                                sourceBackend.openInput(location).use { original ->
                                    copyWithEdits(original, output, edits)
                                }
                            }
                        } catch (error: Throwable) {
                            runCatching { sourceBackend.delete(destination.location) }
                            throw error
                        }
                        destination.location
                    }}.onSuccess { toast("Cópia salva") }.onFailure { showError(it.message ?: "Falha em Salvar como") }
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun copyWithEdits(input: java.io.InputStream, output: java.io.OutputStream, edits: Map<Long, Byte>) {
        val buffer = ByteArray(128 * 1024)
        var absolute = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            edits.entries.filter { it.key >= absolute && it.key < absolute + count }.forEach { (position, byte) ->
                buffer[(position - absolute).toInt()] = byte
            }
            output.write(buffer, 0, count)
            absolute += count
        }
        output.flush()
    }

    private fun copySelected(asHex: Boolean) {
        if (selectionOffset < 0 || selectionLength <= 0) { toast("Selecione um range primeiro"); return }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { readCurrentRange(selectionOffset, selectionLength.coerceAtMost(MAX_SELECTION_BYTES)) } }
                .onSuccess { bytes ->
                    val value = if (asHex) bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
                    else bytes.toString(Charsets.UTF_8)
                    copyToClipboard(value)
                }.onFailure { showError(it.message ?: "Falha ao copiar") }
        }
    }

    private fun copyPageHex() {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { readCurrentRange(offset, PAGE) } }
                .onSuccess { bytes -> copyToClipboard(bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xff) }) }
                .onFailure { showError(it.message ?: "Falha ao copiar") }
        }
    }

    private fun copyToClipboard(value: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Hex", value))
        toast("Copiado")
    }

    private fun showProperties() {
        AlertDialog.Builder(this).setTitle(displayName)
            .setMessage("Caminho: ${location.displayPath}\nTamanho: ${FileListAdapter.formatBytes(size)}\nAlterações pendentes: ${pending.size}\nPágina: $PAGE bytes")
            .setPositiveButton("OK", null).show()
    }

    private fun twoInputs(firstHint: String, secondHint: String): Pair<LinearLayout, Pair<EditText, EditText>> {
        val first = EditText(this).apply { hint = firstHint; setSingleLine() }
        val second = EditText(this).apply { hint = secondHint; setSingleLine() }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            addView(first); addView(second)
        }
        return box to (first to second)
    }

    private fun parseOffset(raw: String): Long? = raw.trim().let {
        if (it.startsWith("0x", true)) it.drop(2).toLongOrNull(16) else it.toLongOrNull()
    }

    private fun parseHex(raw: String): ByteArray? {
        val compact = raw.replace(Regex("[^0-9A-Fa-f]"), "")
        if (compact.isEmpty() || compact.length % 2 != 0 || compact.length / 2 > MAX_SEARCH_PATTERN) return null
        return runCatching { ByteArray(compact.length / 2) { index -> compact.substring(index * 2, index * 2 + 2).toInt(16).toByte() } }.getOrNull()
    }

    private fun indexOf(buffer: ByteArray, length: Int, pattern: ByteArray): Int {
        val limit = length - pattern.size
        for (i in 0..limit) {
            var same = true
            for (j in pattern.indices) if (buffer[i + j] != pattern[j]) { same = false; break }
            if (same) return i
        }
        return -1
    }

    private fun applyPending(buffer: ByteArray, absoluteStart: Long, length: Int) {
        val end = absoluteStart + length
        pending.subMap(absoluteStart, true, end, false).forEach { (position, value) ->
            val index = (position - absoluteStart).toInt()
            if (index in 0 until length) buffer[index] = value
        }
    }

    private fun directFile(): File? = (location as? FileLocation.Direct)?.path?.let(::File)

    private fun skipFully(input: java.io.InputStream, amount: Long) {
        var remaining = amount
        val scratch = ByteArray(64 * 1024)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) { remaining -= skipped; continue }
            val read = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (read < 0) break
            remaining -= read
        }
    }

    private fun showError(message: String) = AlertDialog.Builder(this).setTitle("Hex Editor").setMessage(message).setPositiveButton("OK", null).show()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private data class HexEdit(val offset: Long, val before: ByteArray, val after: ByteArray)

    companion object {
        private const val PAGE = 8192
        private const val BYTES_PER_LINE = 16
        private const val SEARCH_BUFFER = 128 * 1024
        private const val MAX_SEARCH_PATTERN = 64 * 1024
        private const val MAX_SELECTION_BYTES = 1024 * 1024
        private const val MAX_HISTORY = 100
        private const val MIN_FREE_SPACE = 16L * 1024 * 1024
    }
}

private fun ByteArray.contentEqualsLength(other: ByteArray): Boolean = size == other.size
private fun <T> ArrayDeque<T>.removeLastOrNull(): T? = if (isEmpty()) null else removeLast()
