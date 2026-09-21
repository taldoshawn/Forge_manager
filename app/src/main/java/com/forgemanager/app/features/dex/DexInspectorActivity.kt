package com.forgemanager.app.features.dex

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.FileProvider
import com.forgemanager.app.core.file.FileLocation
import com.forgemanager.app.core.file.putFileLocation
import com.forgemanager.app.core.ui.ForgeActivity
import com.forgemanager.app.features.settings.UiPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.TreeMap

class DexInspectorActivity : ForgeActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var workspace: DexWorkspace? = null
    private val sources = ArrayList<File>()
    private val ownedImports = ArrayList<File>()
    private lateinit var title: TextView
    private lateinit var summary: TextView
    private lateinit var searchBox: EditText
    private lateinit var list: ListView
    private lateinit var adapter: DexRowAdapter
    private lateinit var explorerButton: Button
    private lateinit var historyButton: Button
    private lateinit var searchButton: Button

    private var trees: Map<String, PackageNode> = emptyMap()
    private val expandedPackages = linkedSetOf<String>()
    private val history = ArrayList<ClassRef>()
    private var searchRows: List<DexRow> = emptyList()
    private var currentTab = Tab.EXPLORER

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial = intent.getStringArrayListExtra(EXTRA_PATHS)
            ?.map(::File)
            ?.filter { it.isFile }
            .orEmpty()
            .ifEmpty { intent.getStringExtra("path")?.let { listOf(File(it)) }.orEmpty() }
        if (initial.isEmpty()) { finish(); return }
        sources += initial.distinctBy { it.absolutePath }
        setContentView(buildUi())
        load()
    }

    override fun onDestroy() {
        workspace?.close()
        ownedImports.forEach { it.delete() }
        scope.cancel()
        super.onDestroy()
    }

    @Deprecated("SAF compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_ADD_DEX || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.data?.let(uris::add)
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri
        }
        if (uris.isEmpty()) return
        importUris(uris.distinct())
    }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(UiPreferences.background(this@DexInspectorActivity))

        val titleBar = LinearLayout(this@DexInspectorActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            setBackgroundColor(UiPreferences.surface(this@DexInspectorActivity))
        }
        titleBar.addView(tool("←", "Voltar") { finish() })
        val titleBox = LinearLayout(this@DexInspectorActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title = TextView(this@DexInspectorActivity).apply {
            text = "DEX Editor Plus"
            textSize = 17f
            maxLines = 1
            setTextColor(UiPreferences.textPrimary(this@DexInspectorActivity))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        titleBox.addView(title)
        titleBox.addView(TextView(this@DexInspectorActivity).apply {
            text = "DexProject"
            textSize = 10f
            setTextColor(UiPreferences.textSecondary(this@DexInspectorActivity))
        })
        titleBar.addView(titleBox, LinearLayout.LayoutParams(0, -1, 1f))
        titleBar.addView(tool("+DEX", "Adicionar um ou vários DEX") { chooseDexFiles() })
        titleBar.addView(tool("SMALI", "Abrir Smali/DEX Studio") { openSmali() })
        titleBar.addView(tool("⋮", "Mais") { showMore(it) })
        addView(titleBar, LinearLayout.LayoutParams(-1, dp(58)))

        summary = TextView(this@DexInspectorActivity).apply {
            setTextColor(UiPreferences.textSecondary(this@DexInspectorActivity))
            textSize = 10.5f
            setPadding(dp(12), dp(5), dp(12), dp(7))
            text = "Carregando DEX…"
            setBackgroundColor(UiPreferences.surface(this@DexInspectorActivity))
        }
        addView(summary)

        val tabs = LinearLayout(this@DexInspectorActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(UiPreferences.elevatedSurface(this@DexInspectorActivity))
        }
        explorerButton = tabButton("EXPLORER") { switchTab(Tab.EXPLORER) }
        historyButton = tabButton("HISTORY") { switchTab(Tab.HISTORY) }
        searchButton = tabButton("SEARCH") { switchTab(Tab.SEARCH) }
        tabs.addView(explorerButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        tabs.addView(historyButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        tabs.addView(searchButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        addView(tabs)

        searchBox = EditText(this@DexInspectorActivity).apply {
            hint = "Classe, método, campo, string; /regex"
            setSingleLine()
            textSize = 13f
            setTextColor(UiPreferences.textPrimary(this@DexInspectorActivity))
            setHintTextColor(UiPreferences.textSecondary(this@DexInspectorActivity))
            setBackgroundColor(UiPreferences.surface(this@DexInspectorActivity))
            setPadding(dp(12), 0, dp(12), 0)
            visibility = View.GONE
            setOnEditorActionListener { _, _, _ -> runSearch(text.toString()); true }
        }
        addView(searchBox, LinearLayout.LayoutParams(-1, dp(48)))

        adapter = DexRowAdapter()
        list = ListView(this@DexInspectorActivity).apply {
            dividerHeight = 0
            isFastScrollEnabled = true
            setBackgroundColor(UiPreferences.background(this@DexInspectorActivity))
            adapter = this@DexInspectorActivity.adapter
            setOnItemClickListener { _, _, position, _ -> onRowClicked(this@DexInspectorActivity.adapter.getItem(position)) }
            setOnItemLongClickListener { _, _, position, _ ->
                val row = this@DexInspectorActivity.adapter.getItem(position)
                copyText(row.copyValue())
                true
            }
        }
        addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        updateTabs()
    }

    private fun chooseDexFiles() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }, REQ_ADD_DEX)
    }

    private fun importUris(uris: List<Uri>) {
        summary.text = "Importando ${uris.size} arquivo(s)…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val dir = File(cacheDir, "dex-imports").apply { mkdirs() }
                    uris.mapIndexed { index, uri ->
                        val name = queryDisplayName(uri)
                            ?.replace(Regex("[^A-Za-z0-9._() -]"), "_")
                            ?.take(120)
                            ?.ifBlank { null }
                            ?: "import-${System.nanoTime()}-$index.dex"
                        val target = File(dir, "${System.nanoTime()}-$name")
                        contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(target).use { output -> input.copyTo(output, 128 * 1024) }
                        } ?: error("Não foi possível ler $name")
                        require(target.length() in 1..MAX_IMPORT_BYTES) { "$name é grande demais" }
                        target
                    }
                }
            }.onSuccess { imported ->
                ownedImports += imported
                sources += imported
                val unique = sources.distinctBy { it.absolutePath }
                sources.clear(); sources += unique
                history.clear(); expandedPackages.clear(); searchRows = emptyList()
                load()
            }.onFailure { showError(it.message ?: "Falha ao importar DEX") }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor: Cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun load() {
        val inputSources = sources.toList()
        summary.text = "Carregando ${inputSources.size} fonte(s) DEX…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val ws = DexWorkspace.open(inputSources, cacheDir)
                    val built = ws.dexFiles.associate { it.name to buildTree(it.classes) }
                    ws to built
                }
            }.onSuccess { (ws, built) ->
                workspace?.close()
                workspace = ws
                trees = built
                title.text = if (sources.size > 1) "DEX Editor Plus • ${sources.size} fontes" else "DEX Editor Plus"
                summary.text = buildString {
                    append("DEX ").append(ws.dexFiles.size)
                    append("  •  classes ").append(ws.dexFiles.sumOf { it.classes.size })
                    append("  •  methods ").append(ws.dexFiles.sumOf { it.methods.size })
                    append("  •  fields ").append(ws.dexFiles.sumOf { it.fields.size })
                }
                switchTab(Tab.EXPLORER)
            }.onFailure { showError(it.message ?: "DEX inválido") }
        }
    }

    private fun switchTab(tab: Tab) {
        currentTab = tab
        searchBox.visibility = if (tab == Tab.SEARCH) View.VISIBLE else View.GONE
        updateTabs()
        when (tab) {
            Tab.EXPLORER -> showExplorer()
            Tab.HISTORY -> showHistory()
            Tab.SEARCH -> {
                adapter.submit(searchRows.ifEmpty { listOf(DexRow.Message("Digite uma busca e pressione Enter")) })
                searchBox.requestFocus()
            }
        }
    }

    private fun updateTabs() {
        val accent = UiPreferences.accent(this)
        val normal = UiPreferences.textSecondary(this)
        explorerButton.setTextColor(if (currentTab == Tab.EXPLORER) accent else normal)
        historyButton.setTextColor(if (currentTab == Tab.HISTORY) accent else normal)
        searchButton.setTextColor(if (currentTab == Tab.SEARCH) accent else normal)
    }

    private fun showExplorer() {
        val ws = workspace ?: return
        val rows = ArrayList<DexRow>()
        for (dex in ws.dexFiles) {
            rows += DexRow.DexHeader(dex.name, dex.classes.size)
            val root = trees[dex.name] ?: continue
            root.classes.sorted().forEach { rows += classRow(dex.name, it, 1) }
            root.children.values.forEach { appendPackageRows(rows, dex.name, it, 1) }
        }
        adapter.submit(rows.ifEmpty { listOf(DexRow.Message("Nenhuma classe")) })
    }

    private fun appendPackageRows(out: MutableList<DexRow>, dexName: String, node: PackageNode, depth: Int) {
        val key = "$dexName:${node.path}"
        val expanded = key in expandedPackages
        out += DexRow.Package(dexName, node.path, node.name, depth, expanded, node.totalClasses())
        if (!expanded) return
        node.classes.sorted().forEach { out += classRow(dexName, it, depth + 1) }
        node.children.values.forEach { appendPackageRows(out, dexName, it, depth + 1) }
    }

    private fun classRow(dexName: String, descriptor: String, depth: Int): DexRow.Class {
        val simple = descriptor.removePrefix("L").removeSuffix(";").substringAfterLast('/').replace('$', '·')
        return DexRow.Class(dexName, descriptor, simple, depth)
    }

    private fun showHistory() {
        val rows = history.asReversed().mapIndexed { index, ref ->
            DexRow.Class(ref.dexName, ref.descriptor, "${index + 1}. ${displayClass(ref.descriptor)}", 0)
        }
        adapter.submit(rows.ifEmpty { listOf(DexRow.Message("Nenhuma classe aberta nesta sessão")) })
    }

    private fun runSearch(raw: String) {
        val ws = workspace ?: return
        val query = raw.trim()
        if (query.isEmpty()) return
        val regex = query.startsWith('/') && query.length > 1
        val body = if (regex) query.drop(1) else query
        summary.text = "Buscando…"
        scope.launch {
            val result = runCatching { withContext(Dispatchers.Default) { ws.search(body, regex) } }
            result.onSuccess { values ->
                searchRows = values.take(MAX_VISIBLE).map { value ->
                    val dexName = value.substringAfter('[').substringBefore(']')
                    val rawValue = value.substringAfter("] ", value)
                    val descriptor = descriptorFromResult(rawValue)
                    DexRow.SearchResult(dexName, rawValue, descriptor)
                }
                adapter.submit(searchRows.ifEmpty { listOf(DexRow.Message("Nenhum resultado")) })
                summary.text = "${values.size} resultado(s)${if (values.size > MAX_VISIBLE) "  •  exibindo $MAX_VISIBLE" else ""}"
            }.onFailure {
                summary.text = sources.joinToString { it.name }
                showError("Busca inválida: ${it.message ?: "erro"}")
            }
        }
    }

    private fun onRowClicked(row: DexRow) {
        when (row) {
            is DexRow.Package -> {
                val key = "${row.dexName}:${row.path}"
                if (!expandedPackages.add(key)) expandedPackages.remove(key)
                showExplorer()
            }
            is DexRow.Class -> openClass(row.dexName, row.descriptor)
            is DexRow.SearchResult -> row.descriptor?.let { openClass(row.dexName, it) } ?: copyText(row.value)
            else -> Unit
        }
    }

    private fun openClass(dexName: String, descriptor: String) {
        val dex = workspace?.dexFiles?.firstOrNull { it.name == dexName } ?: return
        history.removeAll { it.dexName == dexName && it.descriptor == descriptor }
        history += ClassRef(dexName, descriptor)
        val info = dex.classInfo[descriptor]
        val methods = dex.methods.filter { it.startsWith("$descriptor->") }
        val fields = dex.fields.filter { it.startsWith("$descriptor->") }
        val body = buildString {
            append(displayClass(descriptor)).append('\n')
            append(descriptor).append("\n\n")
            info?.superclass?.let { append("Super: ").append(displayClass(it)).append('\n') }
            if (!info?.interfaces.isNullOrEmpty()) {
                append("Interfaces:\n")
                info!!.interfaces.forEach { append("  • ").append(displayClass(it)).append('\n') }
            }
            append("\nFIELDS (${fields.size})\n")
            fields.take(MAX_MEMBERS).forEach { append("  ").append(it.substringAfter("->")).append('\n') }
            if (fields.size > MAX_MEMBERS) append("  … ${fields.size - MAX_MEMBERS} omitidos\n")
            append("\nMETHODS (${methods.size})\n")
            methods.take(MAX_MEMBERS).forEach { append("  ").append(it.substringAfter("->")).append('\n') }
            if (methods.size > MAX_MEMBERS) append("  … ${methods.size - MAX_MEMBERS} omitidos\n")
        }
        AlertDialog.Builder(this).setTitle("Classe — $dexName")
            .setMessage(body)
            .setPositiveButton("Abrir como Smali") { _, _ -> openSmali(dexName, descriptor) }
            .setNeutralButton("Copiar descriptor") { _, _ -> copyText(descriptor) }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun sourceForDexName(dexName: String): File? {
        val containerName = dexName.substringBefore('!')
        return sources.firstOrNull { it.name == containerName || it.name == dexName }
    }

    private fun openSmali(dexName: String? = null, filterDescriptor: String? = null) {
        val target = dexName?.let(::sourceForDexName)
        if (target == null && sources.size > 1) {
            AlertDialog.Builder(this).setTitle("Abrir no Smali Studio")
                .setItems(sources.map { it.name }.toTypedArray()) { _, which -> launchSmali(sources[which], filterDescriptor) }
                .setNegativeButton("Cancelar", null).show()
            return
        }
        launchSmali(target ?: sources.firstOrNull() ?: return, filterDescriptor)
    }

    private fun launchSmali(source: File, filterDescriptor: String?) {
        if (!source.isFile) return
        val intent = Intent(this, SmaliStudioActivity::class.java)
            .putFileLocation(FileLocation.Direct(source.path), source.name)
        filterDescriptor?.let { intent.putExtra(SmaliStudioActivity.EXTRA_CLASS_DESCRIPTOR, it) }
        startActivity(intent)
    }

    private fun showMore(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Adicionar DEX")
            menu.add("Exportar relatório")
            menu.add("Copiar resumo")
            menu.add("Recolher packages")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Adicionar DEX" -> chooseDexFiles()
                    "Exportar relatório" -> exportReport()
                    "Copiar resumo" -> copyText(summary.text.toString())
                    "Recolher packages" -> { expandedPackages.clear(); if (currentTab == Tab.EXPLORER) showExplorer() }
                }
                true
            }
            show()
        }
    }

    private fun exportReport() {
        val ws = workspace ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val report = File(cacheDir, "dex-report-${System.nanoTime()}.txt")
                    report.bufferedWriter().use { writer ->
                        writer.appendLine("Forge Manager — DEX report")
                        sources.forEach { writer.appendLine("Source: ${it.path}") }
                        ws.dexFiles.forEach { dex ->
                            writer.appendLine()
                            writer.appendLine("=== ${dex.name} ===")
                            writer.appendLine("Classes: ${dex.classes.size}")
                            writer.appendLine("Methods: ${dex.methods.size}")
                            writer.appendLine("Fields: ${dex.fields.size}")
                            writer.appendLine("Strings: ${dex.strings.size}")
                            writer.appendLine("\n[Classes]")
                            dex.classes.forEach(writer::appendLine)
                            writer.appendLine("\n[Methods]")
                            dex.methods.forEach(writer::appendLine)
                            writer.appendLine("\n[Fields]")
                            dex.fields.forEach(writer::appendLine)
                        }
                    }
                    report
                }
            }.onSuccess { report ->
                val uri = FileProvider.getUriForFile(this@DexInspectorActivity, "$packageName.files", report)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Exportar relatório DEX"))
            }.onFailure { showError(it.message ?: "Falha ao exportar") }
        }
    }

    private fun buildTree(classes: List<String>): PackageNode {
        val root = PackageNode("", "")
        for (descriptor in classes) {
            val body = descriptor.removePrefix("L").removeSuffix(";")
            val parts = body.split('/').filter(String::isNotBlank)
            if (parts.isEmpty()) { root.classes += descriptor; continue }
            var node = root
            if (parts.size > 1) {
                for (part in parts.dropLast(1)) {
                    val path = if (node.path.isEmpty()) part else "${node.path}/$part"
                    node = node.children.getOrPut(part) { PackageNode(part, path) }
                }
            }
            node.classes += descriptor
        }
        return root
    }

    private fun descriptorFromResult(value: String): String? {
        if (!value.startsWith('L')) return null
        val end = value.indexOf(';')
        return if (end > 1) value.substring(0, end + 1) else null
    }

    private fun displayClass(descriptor: String): String = descriptor
        .removePrefix("L").removeSuffix(";").replace('/', '.').replace('$', '·')

    private fun copyText(value: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("DEX", value))
        android.widget.Toast.makeText(this, "Copiado", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun tool(label: String, description: String, action: (View) -> Unit) = Button(this).apply {
        text = label
        contentDescription = description
        textSize = if (label.length > 2) 8.5f else 15f
        minWidth = dp(42)
        minimumWidth = dp(42)
        setTextColor(UiPreferences.textPrimary(this@DexInspectorActivity))
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setOnClickListener { action(it) }
    }

    private fun tabButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 10.5f
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setOnClickListener { action() }
    }

    private fun showError(message: String) = runCatching {
        if (!isFinishing && !isDestroyed) AlertDialog.Builder(this)
            .setTitle("DEX Editor Plus")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private inner class DexRowAdapter : BaseAdapter() {
        private var rows: List<DexRow> = emptyList()
        fun submit(value: List<DexRow>) { rows = value; notifyDataSetChanged() }
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = rows.getOrNull(position)?.hashCode()?.toLong() ?: -1L
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = getItem(position)
            val view = (convertView as? TextView) ?: TextView(this@DexInspectorActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(5), dp(8), dp(5))
                minHeight = dp(42)
            }
            view.setBackgroundColor(UiPreferences.background(this@DexInspectorActivity))
            view.textSize = when (row) { is DexRow.DexHeader -> 12f; else -> 13f }
            view.setTextColor(when (row) {
                is DexRow.DexHeader -> UiPreferences.accent(this@DexInspectorActivity)
                is DexRow.Message -> UiPreferences.textSecondary(this@DexInspectorActivity)
                else -> UiPreferences.textPrimary(this@DexInspectorActivity)
            })
            val depth = when (row) {
                is DexRow.Package -> row.depth
                is DexRow.Class -> row.depth
                else -> 0
            }
            view.setPadding(dp(12 + depth * 18), dp(5), dp(8), dp(5))
            view.text = when (row) {
                is DexRow.DexHeader -> "◆  ${row.name}   •   ${row.classCount} classes"
                is DexRow.Package -> "${if (row.expanded) "▾" else "▸"}  ▣  ${row.label}   · ${row.classCount}"
                is DexRow.Class -> "●  ${row.label}"
                is DexRow.SearchResult -> "[${row.dexName}]  ${row.value}"
                is DexRow.Message -> row.value
            }
            return view
        }
    }

    private data class PackageNode(
        val name: String,
        val path: String,
        val children: TreeMap<String, PackageNode> = TreeMap(),
        val classes: MutableList<String> = ArrayList()
    ) {
        fun totalClasses(): Int = classes.size + children.values.sumOf { it.totalClasses() }
    }

    private data class ClassRef(val dexName: String, val descriptor: String)
    private enum class Tab { EXPLORER, HISTORY, SEARCH }

    private sealed class DexRow {
        data class DexHeader(val name: String, val classCount: Int) : DexRow()
        data class Package(val dexName: String, val path: String, val label: String, val depth: Int, val expanded: Boolean, val classCount: Int) : DexRow()
        data class Class(val dexName: String, val descriptor: String, val label: String, val depth: Int) : DexRow()
        data class SearchResult(val dexName: String, val value: String, val descriptor: String?) : DexRow()
        data class Message(val value: String) : DexRow()
        fun copyValue(): String = when (this) {
            is DexHeader -> name
            is Package -> path
            is Class -> descriptor
            is SearchResult -> value
            is Message -> value
        }
    }

    companion object {
        const val EXTRA_PATHS = "dex_paths"
        private const val REQ_ADD_DEX = 733
        private const val MAX_VISIBLE = 5_000
        private const val MAX_MEMBERS = 2_000
        private const val MAX_IMPORT_BYTES = 256L * 1024 * 1024
    }
}
