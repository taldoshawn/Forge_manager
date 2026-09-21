package com.forgemanager.app.features.dex

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.forgemanager.app.ForgeApplication
import com.forgemanager.app.core.file.FileLocation
import com.forgemanager.app.core.file.fileDisplayName
import com.forgemanager.app.core.file.putFileLocation
import com.forgemanager.app.core.file.readFileLocation
import com.forgemanager.app.core.ui.ForgeActivity
import com.forgemanager.app.features.editor.TextEditorActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jf.baksmali.Baksmali
import org.jf.baksmali.BaksmaliOptions
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

class SmaliStudioActivity : ForgeActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }

    private lateinit var location: FileLocation
    private lateinit var displayName: String
    private lateinit var workspace: File
    private lateinit var inputDex: File
    private lateinit var smaliDir: File
    private lateinit var rebuiltDex: File
    private lateinit var status: TextView
    private lateinit var query: EditText
    private lateinit var list: ListView

    private var files: List<File> = emptyList()
    private var rows: List<Row> = emptyList()
    private var disassembled = false
    private var rebuilt = false
    private var mode = SearchMode.CLASSES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        location = intent.readFileLocation() ?: run { finish(); return }
        displayName = intent.fileDisplayName()
            ?: location.displayPath.substringAfterLast('/').substringAfterLast("!/").ifBlank { "classes.dex" }
        val id = Integer.toHexString(location.displayPath.hashCode())
        workspace = File(cacheDir, "smali-studio/$id").apply { mkdirs() }
        inputDex = File(workspace, "input.dex")
        smaliDir = File(workspace, "smali")
        rebuiltDex = File(workspace, "rebuilt.dex")
        setContentView(buildUi())
        materializeAndDisassemble()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(16, 17, 20))

        val top = LinearLayout(this@SmaliStudioActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(23, 24, 28))
        }
        top.addView(button("←") { finish() })
        status = TextView(this@SmaliStudioActivity).apply {
            setTextColor(Color.WHITE)
            text = displayName
            maxLines = 2
            setPadding(dp(6), 0, dp(6), 0)
        }
        top.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(button("REBUILD") { rebuild() }.apply { textSize = 9f })
        top.addView(button("APLICAR") { applyRebuilt() }.apply { textSize = 9f })
        addView(top, LinearLayout.LayoutParams(-1, dp(54)))

        val modes = LinearLayout(this@SmaliStudioActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(29, 31, 36))
            SearchMode.entries.forEach { m ->
                addView(small(m.label) { mode = m; refreshResults() })
            }
        }
        addView(HorizontalScrollView(this@SmaliStudioActivity).apply {
            isHorizontalScrollBarEnabled = false
            addView(modes)
        }, LinearLayout.LayoutParams(-1, dp(42)))

        query = EditText(this@SmaliStudioActivity).apply {
            hint = "Buscar no Smali atual"
            setSingleLine()
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.rgb(29, 31, 36))
            addTextChangedListener(SimpleTextWatcher { refreshResults() })
        }
        addView(query, LinearLayout.LayoutParams(-1, dp(46)))

        list = ListView(this@SmaliStudioActivity).apply {
            setBackgroundColor(Color.rgb(16, 17, 20))
            divider = null
            setOnItemClickListener { _, _, position, _ -> rows.getOrNull(position)?.let(::openRow) }
            setOnItemLongClickListener { _, _, position, _ -> rows.getOrNull(position)?.let(::showRowInfo); true }
        }
        addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        minWidth = dp(44)
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { action() }
    }

    private fun small(label: String, action: () -> Unit) = button(label, action).apply {
        textSize = 9f
        setPadding(dp(10), 0, dp(10), 0)
    }

    private fun materializeAndDisassemble() {
        status.text = "Preparando $displayName…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (smaliDir.exists()) smaliDir.deleteRecursively()
                    require(smaliDir.mkdirs() || smaliDir.isDirectory) { "Falha ao preparar workspace Smali" }
                    rebuiltDex.delete()

                    val backend = graph.resolver.backendFor(location)
                    val node = backend.stat(location)
                    require(node.size in 1..MAX_DEX_BYTES) { "DEX vazio ou grande demais" }
                    backend.openInput(location).use { input ->
                        FileOutputStream(inputDex).buffered(128 * 1024).use { output -> input.copyTo(output, 128 * 1024) }
                    }
                    validateDex(inputDex)
                    val dex = DexFileFactory.loadDexFile(inputDex, Opcodes.getDefault())
                    val options = BaksmaliOptions().apply { apiLevel = API_LEVEL }
                    if (!Baksmali.disassembleDexFile(dex, smaliDir, workers(), options)) {
                        error("Baksmali não conseguiu desmontar o DEX")
                    }
                    smaliDir.walkTopDown()
                        .onEnter { !Files.isSymbolicLink(it.toPath()) }
                        .filter { it.isFile && it.extension.equals("smali", ignoreCase = true) }
                        .take(MAX_LISTED_FILES)
                        .toList()
                }
            }.onSuccess {
                if (!canTouchUi()) return@onSuccess
                files = it
                disassembled = true
                rebuilt = false
                val descriptor = intent.getStringExtra(EXTRA_CLASS_DESCRIPTOR)
                if (!descriptor.isNullOrBlank()) {
                    query.setText(descriptor.removePrefix("L").removeSuffix(";").replace('/', File.separatorChar))
                } else refreshResults()
                status.text = "$displayName • ${files.size} classes Smali"
            }.onFailure { showError(cleanError(it)) }
        }
    }

    private fun refreshResults() {
        if (!disassembled || !::list.isInitialized || !canTouchUi()) return
        val search = query.text?.toString().orEmpty().trim()
        status.text = "$displayName • buscando ${mode.label.lowercase(Locale.ROOT)}…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { buildRows(mode, search) } }
                .onSuccess {
                    if (!canTouchUi()) return@onSuccess
                    rows = it
                    list.adapter = ArrayAdapter(this@SmaliStudioActivity, android.R.layout.simple_list_item_1, it.map(Row::display))
                    status.text = "$displayName • ${it.size} resultado(s) • ${mode.label}"
                }
                .onFailure { showError(cleanError(it)) }
        }
    }

    private fun buildRows(searchMode: SearchMode, search: String): List<Row> {
        if (searchMode == SearchMode.CLASSES) {
            return files.asSequence()
                .map { file -> FileRow(file, file.relativeTo(smaliDir).path) }
                .filter { search.isBlank() || it.relative.contains(search, ignoreCase = true) }
                .take(MAX_RESULTS)
                .toList()
        }

        val result = ArrayList<Row>()
        for (file in files) {
            if (result.size >= MAX_RESULTS) break
            file.useLines { lines ->
                lines.forEachIndexed { index, raw ->
                    if (result.size >= MAX_RESULTS) return@forEachIndexed
                    val trimmed = raw.trim()
                    val matchesMode = when (searchMode) {
                        SearchMode.METHODS -> trimmed.startsWith(".method ")
                        SearchMode.FIELDS -> trimmed.startsWith(".field ")
                        SearchMode.INVOKES -> trimmed.startsWith("invoke-")
                        SearchMode.STRINGS -> trimmed.startsWith("const-string") || trimmed.startsWith("const-string/jumbo")
                        SearchMode.LABELS -> trimmed.startsWith(":")
                        SearchMode.CLASSES -> false
                    }
                    if (!matchesMode) return@forEachIndexed
                    if (search.isNotBlank() && !trimmed.contains(search, ignoreCase = true) &&
                        !file.relativeTo(smaliDir).path.contains(search, ignoreCase = true)) return@forEachIndexed
                    result += HitRow(file, index + 1, trimmed, file.relativeTo(smaliDir).path)
                }
            }
        }
        return result
    }

    private fun openRow(row: Row) {
        if (!canTouchUi()) return
        val file = row.file
        val intent = Intent(this, TextEditorActivity::class.java)
            .putFileLocation(FileLocation.Direct(file.path), file.name)
        if (row is HitRow) {
            intent.putExtra(EXTRA_REQUESTED_LINE, row.line)
            toast("${row.relative}:${row.line}")
        }
        startActivity(intent)
    }

    private fun showRowInfo(row: Row) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { inspectSmaliFile(row.file) } }
                .onSuccess {
                    if (!canTouchUi()) return@onSuccess
                    runCatching {
                        AlertDialog.Builder(this@SmaliStudioActivity)
                            .setTitle(row.relative)
                            .setMessage(it)
                            .setPositiveButton("Abrir") { _, _ -> openRow(row) }
                            .setNegativeButton("Fechar", null)
                            .show()
                    }
                }
                .onFailure { showError(cleanError(it)) }
        }
    }

    private fun inspectSmaliFile(file: File): String {
        var className = file.nameWithoutExtension
        var fields = 0
        var methods = 0
        var labels = 0
        var localsMax = 0
        var registersMax = 0
        var vMax = -1
        var pMax = -1
        file.forEachLine { raw ->
            val line = raw.trim()
            when {
                line.startsWith(".class ") -> className = line.substringAfterLast(' ')
                line.startsWith(".field ") -> fields++
                line.startsWith(".method ") -> methods++
                line.startsWith(":") -> labels++
                line.startsWith(".locals ") -> localsMax = maxOf(localsMax, line.substringAfter(".locals ").trim().toIntOrNull() ?: 0)
                line.startsWith(".registers ") -> registersMax = maxOf(registersMax, line.substringAfter(".registers ").trim().toIntOrNull() ?: 0)
            }
            REGISTER_REGEX.findAll(line).forEach { match ->
                val index = match.groupValues[2].toIntOrNull() ?: return@forEach
                if (match.groupValues[1] == "v") vMax = maxOf(vMax, index) else pMax = maxOf(pMax, index)
            }
        }
        return buildString {
            append("Classe: ").append(className).append('\n')
            append("Arquivo: ").append(file.relativeTo(smaliDir).path).append('\n')
            append("Campos: ").append(fields).append('\n')
            append("Métodos: ").append(methods).append('\n')
            append("Labels: ").append(labels).append('\n')
            append("Maior .locals: ").append(localsMax).append('\n')
            append("Maior .registers: ").append(registersMax).append('\n')
            append("Maior v*: ").append(if (vMax >= 0) "v$vMax" else "—").append('\n')
            append("Maior p*: ").append(if (pMax >= 0) "p$pMax" else "—")
        }
    }

    private fun rebuild() {
        if (!disassembled) { toast("Desmonte o DEX primeiro"); return }
        status.text = "Recompilando Smali…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    rebuiltDex.delete()
                    val options = SmaliOptions().apply {
                        outputDexFile = rebuiltDex.path
                        apiLevel = API_LEVEL
                        jobs = workers()
                    }
                    if (!Smali.assemble(options, listOf(smaliDir.path))) error("Smali encontrou erros")
                    require(rebuiltDex.isFile && rebuiltDex.length() > 0) { "Rebuild não produziu DEX" }
                    validateDex(rebuiltDex)
                    rebuiltDex.length()
                }
            }.onSuccess {
                if (!canTouchUi()) return@onSuccess
                rebuilt = true
                status.text = "$displayName • rebuild validado • ${it / 1024} KB"
                toast("DEX recompilado e validado")
            }.onFailure {
                rebuilt = false
                showError(cleanError(it))
            }
        }
    }

    private fun applyRebuilt() {
        if (!rebuilt || !rebuiltDex.isFile) { toast("Faça o rebuild antes"); return }
        if (!canTouchUi()) return
        runCatching {
            AlertDialog.Builder(this)
                .setTitle("Aplicar DEX recompilado?")
                .setMessage("O DEX validado substituirá o local original. A escrita será transacional quando o backend permitir.")
                .setPositiveButton("Aplicar") { _, _ ->
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { applyValidatedDex() } }
                            .onSuccess {
                                if (canTouchUi()) {
                                    toast("DEX aplicado")
                                    status.text = "$displayName • aplicado"
                                }
                            }
                            .onFailure { showError(cleanError(it)) }
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private suspend fun applyValidatedDex() {
        validateDex(rebuiltDex)
        val backend = graph.resolver.backendFor(location, write = true)
        val direct = location as? FileLocation.Direct
        if (direct != null && backend.id == "direct") {
            val target = File(direct.path)
            val parent = target.parentFile ?: error("Diretório de destino inválido")
            val temp = File(parent, ".${target.name}.${System.nanoTime()}.forge.tmp")
            val oldMode = runCatching { android.system.Os.stat(target.path).st_mode and 0xFFF }.getOrNull()
            try {
                rebuiltDex.inputStream().use { input ->
                    FileOutputStream(temp).use { output ->
                        input.copyTo(output, 128 * 1024)
                        output.flush()
                        output.fd.sync()
                    }
                }
                validateDex(temp)
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
            rebuiltDex.inputStream().use { it.copyTo(output, 128 * 1024) }
            output.flush()
        }
    }

    private fun validateDex(file: File) {
        DexFileFactory.loadDexFile(file, Opcodes.getDefault())
    }

    private fun cleanError(error: Throwable): String {
        val raw = generateSequence(error) { it.cause }
            .mapNotNull { it.message?.takeIf(String::isNotBlank) }
            .firstOrNull()
            ?: error.javaClass.simpleName
        return raw
            .replace(workspace.path + File.separator, "")
            .replace(smaliDir.path + File.separator, "")
            .lineSequence()
            .take(12)
            .joinToString("\n")
            .take(2_000)
    }

    private fun canTouchUi(): Boolean = !isFinishing && !isDestroyed

    private fun showError(message: String) {
        if (!canTouchUi()) return
        runCatching {
            AlertDialog.Builder(this)
                .setTitle("Smali/DEX Studio")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun toast(message: String) {
        if (canTouchUi()) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun workers() = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    private sealed interface Row {
        val file: File
        val relative: String
        fun display(): String
    }

    private data class FileRow(override val file: File, override val relative: String) : Row {
        override fun display() = relative
    }

    private data class HitRow(
        override val file: File,
        val line: Int,
        val text: String,
        override val relative: String
    ) : Row {
        override fun display() = "$relative:$line  $text"
    }

    private enum class SearchMode(val label: String) {
        CLASSES("CLASSES"), METHODS("MÉTODOS"), FIELDS("CAMPOS"), INVOKES("INVOKES"), STRINGS("STRINGS"), LABELS("LABELS")
    }

    private class SimpleTextWatcher(private val callback: () -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = callback()
        override fun afterTextChanged(s: android.text.Editable?) = Unit
    }

    companion object {
        const val EXTRA_CLASS_DESCRIPTOR = "class_descriptor"
        const val EXTRA_REQUESTED_LINE = "requested_line"
        private const val API_LEVEL = 30
        private const val MAX_DEX_BYTES = 256L * 1024 * 1024
        private const val MAX_LISTED_FILES = 50_000
        private const val MAX_RESULTS = 5_000
        private val REGISTER_REGEX = Regex("\\b([vp])(\\d+)\\b")
    }
}
