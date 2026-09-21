package com.forgemanager.app.features.resources

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Xml
import android.view.Gravity
import android.view.View
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
import com.forgemanager.app.core.ui.ForgeActivity
import com.forgemanager.app.features.editor.LineNumberEditText
import com.forgemanager.app.features.settings.UiPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class BinaryResourceEditorActivity : ForgeActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }

    private lateinit var location: FileLocation
    private lateinit var displayName: String
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var xmlEditor: LineNumberEditText
    private lateinit var poolButton: Button
    private lateinit var xmlButton: Button
    private lateinit var structureButton: Button
    private lateinit var bytes: ByteArray
    private lateinit var report: AndroidBinaryResources.Report

    private var entries: List<BinaryStringPools.Entry> = emptyList()
    private var shown: List<BinaryStringPools.Entry> = emptyList()
    private var dirty = false
    private var mode = Mode.POOL
    private var decodedOriginal = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        location = intent.readFileLocation() ?: run { finish(); return }
        displayName = intent.fileDisplayName()
            ?: location.displayPath.substringAfterLast('/').substringAfterLast("!/").ifBlank { "recurso" }
        setContentView(buildUi())
        load()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @Deprecated("Android back compatibility")
    override fun onBackPressed() {
        if (!hasPendingChanges()) {
            super.onBackPressed()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Alterações não salvas")
            .setMessage("Salvar as alterações em $displayName?")
            .setPositiveButton("Salvar") { _, _ -> save { finish() } }
            .setNegativeButton("Descartar") { _, _ -> finish() }
            .setNeutralButton("Cancelar", null)
            .show()
    }

    private fun buildUi() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(UiPreferences.background(this@BinaryResourceEditorActivity))

        val bar = LinearLayout(this@BinaryResourceEditorActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(UiPreferences.surface(this@BinaryResourceEditorActivity))
        }
        bar.addView(button("←") { onBackPressed() })
        status = TextView(this@BinaryResourceEditorActivity).apply {
            setTextColor(UiPreferences.textPrimary(this@BinaryResourceEditorActivity))
            text = displayName
            maxLines = 2
            setPadding(dp(6), 0, dp(6), 0)
        }
        bar.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(button("↻") { load() }.apply { contentDescription = "Recarregar" })
        bar.addView(button("✓") { save() }.apply { contentDescription = "Salvar" })
        addView(bar, LinearLayout.LayoutParams(-1, dp(54)))

        val tabs = LinearLayout(this@BinaryResourceEditorActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(UiPreferences.elevatedSurface(this@BinaryResourceEditorActivity))
        }
        poolButton = tab("POOL") { switchMode(Mode.POOL) }
        xmlButton = tab("XML") { switchMode(Mode.XML) }
        structureButton = tab("STRUCT") { switchMode(Mode.STRUCTURE) }
        tabs.addView(poolButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        tabs.addView(xmlButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        tabs.addView(structureButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        addView(tabs)

        search = EditText(this@BinaryResourceEditorActivity).apply {
            hint = "Filtrar string pool do AXML/ARSC"
            setSingleLine()
            setTextColor(UiPreferences.textPrimary(this@BinaryResourceEditorActivity))
            setHintTextColor(UiPreferences.textSecondary(this@BinaryResourceEditorActivity))
            setBackgroundColor(UiPreferences.surface(this@BinaryResourceEditorActivity))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = filter()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        addView(search, LinearLayout.LayoutParams(-1, dp(48)))

        list = ListView(this@BinaryResourceEditorActivity).apply {
            setBackgroundColor(UiPreferences.background(this@BinaryResourceEditorActivity))
            divider = null
            isFastScrollEnabled = true
            setOnItemClickListener { _, _, position, _ -> shown.getOrNull(position)?.let(::editEntry) }
        }
        addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        xmlEditor = LineNumberEditText(this@BinaryResourceEditorActivity).apply {
            applyPalette(
                UiPreferences.background(this@BinaryResourceEditorActivity),
                UiPreferences.textPrimary(this@BinaryResourceEditorActivity),
                UiPreferences.textSecondary(this@BinaryResourceEditorActivity),
                UiPreferences.divider(this@BinaryResourceEditorActivity)
            )
            setWordWrapEnabled(true)
            visibility = View.GONE
        }
        addView(xmlEditor, LinearLayout.LayoutParams(-1, 0, 1f))
        updateTabs()
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(UiPreferences.textPrimary(this@BinaryResourceEditorActivity))
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        minWidth = dp(44)
        setOnClickListener { action() }
    }

    private fun tab(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 10f
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setOnClickListener { action() }
    }

    private fun switchMode(next: Mode) {
        if (!::bytes.isInitialized) return
        mode = next
        search.visibility = if (next == Mode.POOL) View.VISIBLE else View.GONE
        list.visibility = if (next == Mode.POOL) View.VISIBLE else View.GONE
        xmlEditor.visibility = if (next == Mode.POOL) View.GONE else View.VISIBLE
        when (next) {
            Mode.POOL -> filter()
            Mode.XML -> showXmlMode()
            Mode.STRUCTURE -> showStructureMode()
        }
        updateTabs()
    }

    private fun updateTabs() {
        if (!::poolButton.isInitialized) return
        val accent = UiPreferences.accent(this)
        val normal = UiPreferences.textSecondary(this)
        poolButton.setTextColor(if (mode == Mode.POOL) accent else normal)
        xmlButton.setTextColor(if (mode == Mode.XML) accent else normal)
        structureButton.setTextColor(if (mode == Mode.STRUCTURE) accent else normal)
    }

    private fun showXmlMode() {
        if (report.kind != AndroidBinaryResources.Kind.BINARY_XML) {
            xmlEditor.keyListener = null
            xmlEditor.setText("resources.arsc é uma tabela binária de recursos, não um documento XML único.\n\nUse POOL para editar strings e STRUCT para inspecionar os chunks.")
            return
        }
        runCatching { AndroidBinaryXmlDecoder.decode(bytes) }
            .onSuccess { decoded ->
                if (decodedOriginal.isBlank() || !hasXmlChanges()) {
                    decodedOriginal = decoded
                    xmlEditor.keyListener = android.text.method.TextKeyListener.getInstance()
                    xmlEditor.setText(decoded)
                    xmlEditor.setSelection(0)
                }
            }
            .onFailure {
                xmlEditor.keyListener = null
                xmlEditor.setText("Falha ao decodificar XML normal: ${it.message}")
            }
    }

    private fun showStructureMode() {
        xmlEditor.keyListener = null
        xmlEditor.setText(AndroidBinaryResources.describe(report).take(MAX_STRUCTURE_TEXT))
        xmlEditor.setSelection(0)
    }

    private fun load() {
        if (hasPendingChanges()) {
            AlertDialog.Builder(this)
                .setTitle("Descartar alterações?")
                .setMessage("Recarregar irá descartar alterações ainda não salvas.")
                .setPositiveButton("Recarregar") { _, _ -> dirty = false; decodedOriginal = ""; loadNow() }
                .setNegativeButton("Cancelar", null)
                .show()
        } else loadNow()
    }

    private fun loadNow() {
        status.text = "Lendo $displayName…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val backend = graph.resolver.backendFor(location)
                    val node = backend.stat(location)
                    require(node.size in 1..MAX_BYTES) { "AXML/ARSC vazio ou grande demais" }
                    val output = ByteArrayOutputStream(node.size.coerceAtMost(2L * 1024 * 1024).toInt())
                    backend.openInput(location).use { it.copyTo(output, 64 * 1024) }
                    val data = output.toByteArray()
                    val structural = AndroidBinaryResources.inspect(data)
                    Triple(data, structural, BinaryStringPools.find(data))
                }
            }.onSuccess { (data, structural, strings) ->
                bytes = data
                report = structural
                entries = strings
                dirty = false
                decodedOriginal = ""
                val type = if (structural.kind == AndroidBinaryResources.Kind.BINARY_XML) "AXML" else "ARSC"
                status.text = "$displayName • $type • ${strings.size} strings • ${structural.chunks.size} chunks"
                switchMode(if (structural.kind == AndroidBinaryResources.Kind.BINARY_XML) Mode.XML else Mode.POOL)
            }.onFailure { error(it.message ?: "Falha ao abrir recurso Android binário") }
        }
    }

    private fun filter() {
        if (!::bytes.isInitialized) return
        val query = search.text?.toString().orEmpty()
        shown = if (query.isBlank()) entries else entries.filter { it.value.contains(query, ignoreCase = true) }
        list.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            shown.map { "[${it.index}] ${if (it.utf8) "UTF-8" else "UTF-16"}  ${it.value}" }
        )
    }

    private fun editEntry(entry: BinaryStringPools.Entry) {
        val input = EditText(this).apply {
            setText(entry.value)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("String #${entry.index}")
            .setMessage("Alteração binária conservadora: a string só pode ocupar o espaço já reservado; offsets e chunks não são deslocados.")
            .setView(input)
            .setPositiveButton("Aplicar") { _, _ ->
                runCatching {
                    BinaryStringPools.replace(bytes, entry, input.text.toString())
                    AndroidBinaryResources.inspect(bytes)
                }.onSuccess {
                    dirty = true
                    decodedOriginal = ""
                    entries = BinaryStringPools.find(bytes)
                    filter()
                    status.text = "$displayName • modificado"
                }.onFailure { error(it.message ?: "A alteração não cabe com segurança no string pool") }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Applies XML-view edits only when XML structure stays identical. Values are
     * mapped back to existing string-pool entries; no binary chunk is resized.
     */
    private fun applyXmlEditsIfNeeded() {
        if (report.kind != AndroidBinaryResources.Kind.BINARY_XML || !hasXmlChanges()) return
        val edited = xmlEditor.text.toString()
        val before = parseXmlSnapshot(decodedOriginal)
        val after = parseXmlSnapshot(edited)
        require(before.skeleton == after.skeleton) {
            "A estrutura XML foi alterada. Este modo seguro permite editar valores/textos sem adicionar ou remover nós/atributos."
        }
        require(before.values.size == after.values.size) { "Quantidade de valores XML alterada" }
        val replacements = LinkedHashMap<String, String>()
        before.values.indices.forEach { index ->
            val old = before.values[index]
            val new = after.values[index]
            if (old != new) {
                val previous = replacements.put(old, new)
                require(previous == null || previous == new) {
                    "A mesma string compartilhada recebeu valores diferentes; use POOL para controlar esta alteração."
                }
            }
        }
        replacements.forEach { (old, new) ->
            val matches = BinaryStringPools.find(bytes).filter { it.value == old }
            require(matches.isNotEmpty()) { "Valor '$old' não está armazenado no string pool e não pode ser alterado neste modo." }
            matches.forEach { BinaryStringPools.replace(bytes, it, new) }
        }
        AndroidBinaryResources.inspect(bytes)
        entries = BinaryStringPools.find(bytes)
        dirty = dirty || replacements.isNotEmpty()
        decodedOriginal = AndroidBinaryXmlDecoder.decode(bytes)
        xmlEditor.setText(decodedOriginal)
    }

    private fun parseXmlSnapshot(xml: String): XmlSnapshot {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        val skeleton = ArrayList<String>()
        val values = ArrayList<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    skeleton += "S:${parser.namespace}:${parser.name}:${parser.attributeCount}"
                    for (i in 0 until parser.attributeCount) {
                        skeleton += "A:${parser.getAttributeNamespace(i)}:${parser.getAttributeName(i)}"
                        values += parser.getAttributeValue(i).orEmpty()
                    }
                }
                XmlPullParser.END_TAG -> skeleton += "E:${parser.namespace}:${parser.name}"
                XmlPullParser.TEXT -> parser.text?.takeIf { it.isNotBlank() }?.let { values += it }
            }
            event = parser.next()
        }
        return XmlSnapshot(skeleton, values)
    }

    private fun hasXmlChanges(): Boolean =
        mode == Mode.XML && decodedOriginal.isNotBlank() && ::xmlEditor.isInitialized && xmlEditor.text.toString() != decodedOriginal

    private fun hasPendingChanges(): Boolean = dirty || hasXmlChanges()

    private fun save(after: (() -> Unit)? = null) {
        if (!::bytes.isInitialized) return
        status.text = "Validando $displayName…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.Default) { applyXmlEditsIfNeeded() }
                withContext(Dispatchers.IO) {
                    report = AndroidBinaryResources.inspect(bytes)
                    writeSafely(bytes)
                }
            }.onSuccess {
                dirty = false
                status.text = "$displayName • salvo e validado"
                Toast.makeText(this@BinaryResourceEditorActivity, "Recurso binário salvo", Toast.LENGTH_SHORT).show()
                after?.invoke()
            }.onFailure { error(it.message ?: "Falha ao salvar recurso binário") }
        }
    }

    private suspend fun writeSafely(data: ByteArray) {
        val backend = graph.resolver.backendFor(location, write = true)
        val direct = location as? FileLocation.Direct
        if (direct != null && backend.id == "direct") {
            val target = File(direct.path)
            val parent = target.parentFile ?: throw IllegalStateException("Pasta de destino inválida")
            val temp = File(parent, ".${target.name}.${System.nanoTime()}.forge.tmp")
            try {
                FileOutputStream(temp).use { output ->
                    output.write(data)
                    output.flush()
                    output.fd.sync()
                }
                val oldMode = runCatching { android.system.Os.stat(target.path).st_mode and 0xFFF }.getOrNull()
                try {
                    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                if (oldMode != null) runCatching { android.system.Os.chmod(target.path, oldMode) }
            } finally {
                temp.delete()
            }
            return
        }
        backend.openOutput(location, truncate = true).use { output ->
            output.write(data)
            output.flush()
        }
    }

    private fun error(message: String) = runCatching {
        if (!isFinishing && !isDestroyed) AlertDialog.Builder(this)
            .setTitle("Editor AXML/ARSC")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private enum class Mode { POOL, XML, STRUCTURE }
    private data class XmlSnapshot(val skeleton: List<String>, val values: List<String>)

    companion object {
        private const val MAX_BYTES = 128L * 1024 * 1024
        private const val MAX_STRUCTURE_TEXT = 120_000
    }
}
