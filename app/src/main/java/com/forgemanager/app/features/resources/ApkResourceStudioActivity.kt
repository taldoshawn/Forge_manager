package com.forgemanager.app.features.resources

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
import com.forgemanager.app.archive.ZipSecurity
import com.forgemanager.app.core.file.FileLocation
import com.forgemanager.app.core.file.putFileLocation
import com.forgemanager.app.core.ui.ForgeActivity
import com.forgemanager.app.features.apktools.ApkToolboxActivity
import com.forgemanager.app.features.dex.SmaliStudioActivity
import com.forgemanager.app.features.editor.HexViewerActivity
import com.forgemanager.app.features.editor.TextEditorActivity
import com.forgemanager.app.features.explorer.FileKind
import com.forgemanager.app.features.explorer.FileTypeClassifier
import com.forgemanager.app.features.viewer.ImageViewerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

class ApkResourceStudioActivity : ForgeActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }
    private lateinit var apk: File
    private lateinit var list: ListView
    private lateinit var status: TextView
    private lateinit var filter: EditText
    private var entries: List<Entry> = emptyList()
    private var shown: List<Entry> = emptyList()
    private var pending: Entry? = null

    data class Entry(val name: String, val size: Long, val compressedSize: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_APK_PATH) ?: intent.getStringExtra("path")
        apk = path?.let(::File) ?: run { finish(); return }
        if (!apk.isFile) { finish(); return }
        setContentView(buildUi())
        refresh()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(16, 17, 20))
        val bar = LinearLayout(this@ApkResourceStudioActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(23, 24, 28))
        }
        bar.addView(button("←") { finish() })
        status = TextView(this@ApkResourceStudioActivity).apply {
            setTextColor(Color.WHITE)
            text = apk.name
            maxLines = 2
            setPadding(dp(6), 0, dp(6), 0)
        }
        bar.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(button("↻") { refresh() })
        addView(bar, LinearLayout.LayoutParams(-1, dp(54)))

        val shortcuts = LinearLayout(this@ApkResourceStudioActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(29, 31, 36))
            addView(small("MANIFEST") { entries.firstOrNull { it.name == "AndroidManifest.xml" }?.let(::openEntry) ?: toast("Manifest não encontrado") })
            addView(small("DEX") { entries.firstOrNull { it.name.matches(Regex("classes\\d*\\.dex")) }?.let(::openEntry) ?: toast("DEX não encontrado") })
            addView(small("APK TOOLBOX") { startActivity(Intent(this@ApkResourceStudioActivity, ApkToolboxActivity::class.java).putExtra(ApkToolboxActivity.EXTRA_APK_PATH, apk.path)) })
        }
        addView(HorizontalScrollView(this@ApkResourceStudioActivity).apply {
            isHorizontalScrollBarEnabled = false
            addView(shortcuts)
        }, LinearLayout.LayoutParams(-1, dp(44)))

        filter = EditText(this@ApkResourceStudioActivity).apply {
            hint = "Filtrar res/, assets/, lib/, manifest, DEX…"
            setSingleLine()
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.rgb(29, 31, 36))
            addTextChangedListener(Watcher { applyFilter() })
        }
        addView(filter, LinearLayout.LayoutParams(-1, dp(48)))

        list = ListView(this@ApkResourceStudioActivity).apply {
            setBackgroundColor(Color.rgb(16, 17, 20))
            divider = null
            setOnItemClickListener { _, _, position, _ -> shown.getOrNull(position)?.let(::openEntry) }
            setOnItemLongClickListener { _, _, position, _ -> shown.getOrNull(position)?.let(::actions); true }
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
        textSize = 10f
        setPadding(dp(10), 0, dp(10), 0)
    }

    private fun refresh() {
        status.text = "Inspecionando ${apk.name}…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { inspectArchive() } }
                .onSuccess {
                    entries = it
                    applyFilter()
                    status.text = "${apk.name} • ${it.size} recursos"
                }
                .onFailure { error(it.message ?: "Falha ao ler APK") }
        }
    }

    private fun inspectArchive(): List<Entry> {
        val result = ArrayList<Entry>()
        var count = 0
        var totalUncompressed = 0L
        ZipFile(apk).use { zip ->
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val raw = enumeration.nextElement()
                count++
                require(count <= MAX_ARCHIVE_ENTRIES) { "APK possui entradas demais" }
                val safeName = ZipSecurity.normalizeEntryName(raw.name)
                if (raw.isDirectory) continue
                val size = raw.size.coerceAtLeast(0)
                require(size <= MAX_ENTRY_BYTES) { "Entrada grande demais: $safeName" }
                totalUncompressed += size
                require(totalUncompressed <= MAX_TOTAL_UNCOMPRESSED) { "APK excede o limite seguro descompactado" }
                if (safeName == "AndroidManifest.xml" || safeName == "resources.arsc" ||
                    safeName.startsWith("res/") || safeName.startsWith("assets/") || safeName.startsWith("lib/") ||
                    safeName.matches(Regex("classes\\d*\\.dex"))) {
                    result += Entry(safeName, size, raw.compressedSize.coerceAtLeast(0))
                }
            }
        }
        return result.sortedBy { it.name.lowercase() }
    }

    private fun applyFilter() {
        if (!::list.isInitialized) return
        val query = filter.text?.toString().orEmpty()
        shown = if (query.isBlank()) entries else entries.filter { it.name.contains(query, ignoreCase = true) }
        list.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            shown.map { "${it.name}  •  ${formatSize(it.size)}" }
        )
    }

    private fun openEntry(entry: Entry) {
        val location = FileLocation.Archive(apk.path, entry.name)
        val name = entry.name.substringAfterLast('/')
        val ext = FileTypeClassifier.extensionOf(name)
        when {
            entry.name == "resources.arsc" || ext == "arsc" ->
                startActivity(Intent(this, BinaryResourceEditorActivity::class.java).putFileLocation(location, name))
            ext == "xml" || ext == "axml" -> openXmlSafely(location, name)
            ext == "dex" -> startActivity(Intent(this, SmaliStudioActivity::class.java).putFileLocation(location, name))
            FileTypeClassifier.classify(name, false) == FileKind.IMAGE ->
                startActivity(Intent(this, ImageViewerActivity::class.java).putFileLocation(location, name))
            FileTypeClassifier.classify(name, false) in TEXT_KINDS ->
                startActivity(Intent(this, TextEditorActivity::class.java).putFileLocation(location, name))
            else -> startActivity(Intent(this, HexViewerActivity::class.java).putFileLocation(location, name))
        }
    }

    private fun openXmlSafely(location: FileLocation, name: String) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    graph.archive.openInput(location).use { input ->
                        val prefix = ByteArray(XML_SNIFF_BYTES)
                        val count = input.read(prefix)
                        if (count <= 0) ByteArray(0) else prefix.copyOf(count)
                    }
                }
            }.onSuccess { prefix ->
                when {
                    AndroidBinaryResources.isBinaryXml(prefix) -> {
                        AlertDialog.Builder(this@ApkResourceStudioActivity)
                            .setTitle(name)
                            .setItems(arrayOf("Editor AXML binário", "Hexadecimal")) { _, which ->
                                when (which) {
                                    0 -> startActivity(Intent(this@ApkResourceStudioActivity, BinaryResourceEditorActivity::class.java).putFileLocation(location, name))
                                    1 -> startActivity(Intent(this@ApkResourceStudioActivity, HexViewerActivity::class.java).putFileLocation(location, name))
                                }
                            }.show()
                    }
                    AndroidBinaryResources.isPlainTextXml(prefix) -> {
                        AlertDialog.Builder(this@ApkResourceStudioActivity)
                            .setTitle(name)
                            .setItems(arrayOf("Editor XML", "Hexadecimal")) { _, which ->
                                when (which) {
                                    0 -> startActivity(Intent(this@ApkResourceStudioActivity, TextEditorActivity::class.java).putFileLocation(location, name))
                                    1 -> startActivity(Intent(this@ApkResourceStudioActivity, HexViewerActivity::class.java).putFileLocation(location, name))
                                }
                            }.show()
                    }
                    else -> startActivity(Intent(this@ApkResourceStudioActivity, HexViewerActivity::class.java).putFileLocation(location, name))
                }
            }.onFailure { error(it.message ?: "Falha ao identificar XML") }
        }
    }

    private fun actions(entry: Entry) {
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(arrayOf("Abrir", "Substituir por arquivo…", "Excluir entrada")) { _, which ->
                when (which) {
                    0 -> openEntry(entry)
                    1 -> choose(entry)
                    2 -> delete(entry)
                }
            }.show()
    }

    @Suppress("DEPRECATION")
    private fun choose(entry: Entry) {
        pending = entry
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, REQ_REPLACE)
    }

    @Deprecated("picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_REPLACE || resultCode != RESULT_OK) return
        val entry = pending ?: return
        val uri = data?.data ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Não foi possível abrir o arquivo selecionado")
                    input.use { source ->
                        graph.archive.openOutput(FileLocation.Archive(apk.path, entry.name), true).use { output ->
                            source.copyTo(output, 128 * 1024)
                        }
                    }
                }
            }.onSuccess {
                toast("Entrada substituída")
                refresh()
            }.onFailure { error(it.message ?: "Falha ao substituir") }
        }
    }

    private fun delete(entry: Entry) {
        AlertDialog.Builder(this)
            .setTitle("Excluir recurso?")
            .setMessage(entry.name)
            .setPositiveButton("Excluir") { _, _ ->
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { graph.archive.delete(FileLocation.Archive(apk.path, entry.name)) } }
                        .onSuccess { refresh() }
                        .onFailure { error(it.message ?: "Falha ao excluir") }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun error(message: String) = AlertDialog.Builder(this)
        .setTitle("Resource Studio")
        .setMessage(message)
        .setPositiveButton("OK", null)
        .show()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun formatSize(value: Long) = when {
        value >= 1024L * 1024 -> String.format("%.1f MB", value / (1024.0 * 1024.0))
        value >= 1024 -> String.format("%.1f KB", value / 1024.0)
        else -> "$value B"
    }

    private class Watcher(val callback: () -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = callback()
        override fun afterTextChanged(s: android.text.Editable?) = Unit
    }

    companion object {
        const val EXTRA_APK_PATH = "apk_path"
        private const val REQ_REPLACE = 410
        private const val XML_SNIFF_BYTES = 512
        private const val MAX_ARCHIVE_ENTRIES = 100_000
        private const val MAX_ENTRY_BYTES = 512L * 1024 * 1024
        private const val MAX_TOTAL_UNCOMPRESSED = 2L * 1024 * 1024 * 1024
        private val TEXT_KINDS = setOf(FileKind.CODE, FileKind.SCRIPT, FileKind.WEB, FileKind.MARKDOWN, FileKind.TEXT, FileKind.CONFIG)
    }
}
