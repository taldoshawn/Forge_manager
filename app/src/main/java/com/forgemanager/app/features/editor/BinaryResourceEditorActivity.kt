package com.forgemanager.app.features.editor

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
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

/**
 * Safe compiled-resource editor.
 *
 * Android binary XML and resources.arsc keep most human-readable names/values in RES_STRING_POOL
 * chunks. This editor changes those pools in-place without changing chunk offsets, which makes it
 * suitable for fast edits inside an APK/ZIP backend. A replacement must fit in the original string
 * slot. Larger structural resource edits intentionally require a full resource-table rebuild.
 */
class BinaryResourceEditorActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }
    private lateinit var location: FileLocation
    private lateinit var displayName: String
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var list: ListView
    private var bytes = ByteArray(0)
    private var entries: List<StringEntry> = emptyList()
    private var shown: List<StringEntry> = emptyList()
    private var dirty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        location = intent.readFileLocation() ?: run { finish(); return }
        displayName = intent.fileDisplayName() ?: location.displayPath.substringAfterLast('/').ifBlank { "recurso.bin" }
        setContentView(buildUi())
        load()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @Deprecated("Android back compatibility")
    override fun onBackPressed() {
        if (!dirty) { super.onBackPressed(); return }
        AlertDialog.Builder(this).setTitle("Alterações não salvas")
            .setMessage("Salvar as alterações em $displayName?")
            .setPositiveButton("Salvar") { _, _ -> save { finish() } }
            .setNegativeButton("Descartar") { _, _ -> finish() }
            .setNeutralButton("Cancelar", null).show()
    }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)
        val bar = LinearLayout(this@BinaryResourceEditorActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(6, 9, 13))
            setPadding(dp(4), 0, dp(4), 0)
        }
        bar.addView(button("←") { onBackPressed() })
        status = TextView(this@BinaryResourceEditorActivity).apply {
            text = displayName
            setTextColor(Color.WHITE)
            maxLines = 2
        }
        bar.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(button("SALVAR") { save() })
        addView(bar, LinearLayout.LayoutParams(-1, dp(54)))

        search = EditText(this@BinaryResourceEditorActivity).apply {
            hint = "Buscar string em AXML/ARSC"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(110, 120, 135))
            setBackgroundColor(Color.rgb(8, 11, 16))
            setPadding(dp(12), 0, dp(12), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = filter()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        addView(search, LinearLayout.LayoutParams(-1, dp(50)))
        list = ListView(this@BinaryResourceEditorActivity).apply {
            divider = null
            setBackgroundColor(Color.BLACK)
            setOnItemClickListener { _, _, position, _ -> edit(shown[position]) }
        }
        addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        minWidth = dp(48)
        setOnClickListener { action() }
    }

    private fun load() {
        status.text = "Lendo $displayName…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val backend = graph.resolver.backendFor(location)
                    val size = backend.stat(location).size
                    require(size in 1..MAX_BYTES) { "Arquivo grande demais para edição binária (${size / 1024 / 1024} MB)" }
                    val data = backend.openInput(location).use { readAllLimited(it, MAX_BYTES.toInt()) }
                    val found = ResourceStringPools.parse(data)
                    require(found.isNotEmpty()) { "Nenhum RES_STRING_POOL válido encontrado. O arquivo pode não ser AXML/ARSC compilado." }
                    data to found
                }
            }.onSuccess { (data, found) ->
                bytes = data
                entries = found
                status.text = "$displayName  •  ${found.size} strings"
                filter()
            }.onFailure { showError(it.message ?: "Falha ao abrir recurso") }
        }
    }

    private fun filter() {
        if (!::list.isInitialized) return
        val q = search.text?.toString().orEmpty()
        shown = if (q.isBlank()) entries.take(MAX_VISIBLE) else entries.asSequence()
            .filter { it.value.contains(q, ignoreCase = true) }.take(MAX_VISIBLE).toList()
        val labels = shown.map { "#${it.poolIndex}:${it.stringIndex}   ${it.value}" }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
    }

    private fun edit(entry: StringEntry) {
        val input = EditText(this).apply {
            setText(entry.value)
            setSelection(text.length)
            setSelectAllOnFocus(false)
        }
        AlertDialog.Builder(this).setTitle("Editar string")
            .setMessage("A edição rápida preserva offsets. O novo valor precisa caber no slot binário existente (${entry.slotLength} bytes).")
            .setView(input)
            .setPositiveButton("Aplicar") { _, _ ->
                val value = input.text.toString()
                runCatching { entry.write(bytes, value) }
                    .onSuccess {
                        dirty = true
                        entries = ResourceStringPools.parse(bytes)
                        status.text = "$displayName  •  alterado"
                        filter()
                    }.onFailure { showError(it.message ?: "Valor não cabe no slot") }
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun save(after: (() -> Unit)? = null) {
        if (bytes.isEmpty()) return
        status.text = "Salvando…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val backend = graph.resolver.backendFor(location, write = true)
                    backend.openOutput(location, truncate = true).use { out -> out.write(bytes); out.flush() }
                }
            }.onSuccess {
                dirty = false
                status.text = "$displayName  •  salvo"
                Toast.makeText(this@BinaryResourceEditorActivity, "Recurso salvo", Toast.LENGTH_SHORT).show()
                after?.invoke()
            }.onFailure { showError(it.message ?: "Falha ao salvar") }
        }
    }

    private fun readAllLimited(input: java.io.InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(limit, 512 * 1024))
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            require(total <= limit) { "Arquivo excedeu o limite de edição" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun showError(message: String) = AlertDialog.Builder(this)
        .setTitle("AXML / ARSC")
        .setMessage(message)
        .setPositiveButton("OK", null).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_BYTES = 64L * 1024 * 1024
        private const val MAX_VISIBLE = 8_000
    }
}

private data class StringEntry(
    val poolIndex: Int,
    val stringIndex: Int,
    val value: String,
    val absoluteOffset: Int,
    val slotLength: Int,
    val utf8: Boolean
) {
    fun write(target: ByteArray, newValue: String) {
        val encoded = if (utf8) encodeUtf8(newValue) else encodeUtf16(newValue)
        require(encoded.size <= slotLength) { "O valor codificado precisa de ${encoded.size} bytes; o slot tem $slotLength." }
        encoded.copyInto(target, absoluteOffset)
        target.fill(0, absoluteOffset + encoded.size, absoluteOffset + slotLength)
    }

    private fun encodeUtf8(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val chars = value.length
        return length8(chars) + length8(bytes.size) + bytes + byteArrayOf(0)
    }

    private fun encodeUtf16(value: String): ByteArray {
        val text = value.toByteArray(Charsets.UTF_16LE)
        val units = text.size / 2
        return length16(units) + text + byteArrayOf(0, 0)
    }

    private fun length8(v: Int): ByteArray = if (v <= 0x7F) byteArrayOf(v.toByte())
        else byteArrayOf(((v shr 8) or 0x80).toByte(), (v and 0xFF).toByte())

    private fun length16(v: Int): ByteArray = if (v <= 0x7FFF) byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        else {
            val hi = (v shr 16) or 0x8000
            val lo = v and 0xFFFF
            byteArrayOf((hi and 0xFF).toByte(), ((hi shr 8) and 0xFF).toByte(), (lo and 0xFF).toByte(), ((lo shr 8) and 0xFF).toByte())
        }
}

private object ResourceStringPools {
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val UTF8_FLAG = 0x00000100

    fun parse(data: ByteArray): List<StringEntry> {
        val result = ArrayList<StringEntry>()
        var poolIndex = 0
        var offset = 0
        while (offset + 8 <= data.size) {
            if (u16(data, offset) == RES_STRING_POOL_TYPE) {
                parsePool(data, offset, poolIndex)?.let { pool ->
                    result += pool
                    poolIndex++
                    val size = u32(data, offset + 4).toInt()
                    if (size >= 8) { offset += size; continue }
                }
            }
            offset += 4
        }
        return result
    }

    private fun parsePool(data: ByteArray, base: Int, poolIndex: Int): List<StringEntry>? = runCatching {
        val headerSize = u16(data, base + 2)
        val chunkSize = u32(data, base + 4).toInt()
        val stringCount = u32(data, base + 8).toInt()
        val styleCount = u32(data, base + 12).toInt()
        val flags = u32(data, base + 16).toInt()
        val stringsStart = u32(data, base + 20).toInt()
        val stylesStart = u32(data, base + 24).toInt()
        require(headerSize >= 28 && chunkSize >= headerSize && base + chunkSize <= data.size)
        require(stringCount in 0..2_000_000 && styleCount in 0..2_000_000)
        val indexStart = base + headerSize
        require(indexStart + stringCount * 4L <= base + chunkSize)
        val utf8 = flags and UTF8_FLAG != 0
        val offsets = IntArray(stringCount) { index -> u32(data, indexStart + index * 4).toInt() }
        val stringDataEnd = if (stylesStart > 0) base + stylesStart else base + chunkSize
        val out = ArrayList<StringEntry>(stringCount)
        for (i in 0 until stringCount) {
            val rel = offsets[i]
            val abs = base + stringsStart + rel
            if (abs !in 0 until stringDataEnd) continue
            val nextRel = offsets.asSequence().filter { it > rel }.minOrNull()
            val slotEnd = if (nextRel != null) minOf(base + stringsStart + nextRel, stringDataEnd) else stringDataEnd
            if (slotEnd <= abs) continue
            val decoded = if (utf8) decodeUtf8(data, abs, slotEnd) else decodeUtf16(data, abs, slotEnd)
            out += StringEntry(poolIndex, i, decoded, abs, slotEnd - abs, utf8)
        }
        out
    }.getOrNull()

    private fun decodeUtf8(data: ByteArray, start: Int, end: Int): String {
        var p = start
        val firstChars = data[p++].toInt() and 0xFF
        if (firstChars and 0x80 != 0 && p < end) p++
        if (p >= end) return ""
        val firstBytes = data[p++].toInt() and 0xFF
        val byteLen = if (firstBytes and 0x80 == 0) firstBytes else {
            if (p >= end) return ""
            ((firstBytes and 0x7F) shl 8) or (data[p++].toInt() and 0xFF)
        }
        val available = minOf(byteLen, end - p)
        return data.copyOfRange(p, p + available).toString(Charsets.UTF_8).trimEnd('\u0000')
    }

    private fun decodeUtf16(data: ByteArray, start: Int, end: Int): String {
        var p = start
        if (p + 2 > end) return ""
        val first = u16(data, p); p += 2
        val units = if (first and 0x8000 == 0) first else {
            if (p + 2 > end) return ""
            ((first and 0x7FFF) shl 16) or u16(data, p).also { p += 2 }
        }
        val bytes = minOf(units * 2, end - p).coerceAtLeast(0)
        return data.copyOfRange(p, p + bytes).toString(Charsets.UTF_16LE).trimEnd('\u0000')
    }

    private fun u16(data: ByteArray, o: Int): Int {
        require(o >= 0 && o + 2 <= data.size)
        return (data[o].toInt() and 0xFF) or ((data[o + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32(data: ByteArray, o: Int): Long {
        require(o >= 0 && o + 4 <= data.size)
        return (data[o].toLong() and 0xFF) or ((data[o + 1].toLong() and 0xFF) shl 8) or
            ((data[o + 2].toLong() and 0xFF) shl 16) or ((data[o + 3].toLong() and 0xFF) shl 24)
    }
}
