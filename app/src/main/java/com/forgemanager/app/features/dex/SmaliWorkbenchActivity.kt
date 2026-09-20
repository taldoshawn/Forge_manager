package com.forgemanager.app.features.dex

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Build
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
import com.forgemanager.app.core.file.putFileLocation
import com.forgemanager.app.core.file.readFileLocation
import com.forgemanager.app.features.editor.TextEditorActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SmaliWorkbenchActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }
    private lateinit var source: FileLocation
    private lateinit var displayName: String
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var workspace: File
    private lateinit var materializedDex: File
    private var smaliFiles: List<File> = emptyList()
    private var shown: List<File> = emptyList()
    private val apiLevel = Build.VERSION.SDK_INT.coerceIn(21, 35)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source = intent.readFileLocation() ?: run { finish(); return }
        displayName = intent.fileDisplayName() ?: source.displayPath.substringAfterLast('/').ifBlank { "classes.dex" }
        workspace = File(cacheDir, "smali-work/${System.nanoTime()}")
        materializedDex = File(workspace, "input.dex")
        setContentView(buildUi())
        disassemble()
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { workspace.deleteRecursively() }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::workspace.isInitialized && workspace.exists()) refreshFiles()
    }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)
        val bar = LinearLayout(this@SmaliWorkbenchActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(6, 9, 13))
            setPadding(dp(4), 0, dp(4), 0)
        }
        bar.addView(button("←") { finish() })
        status = TextView(this@SmaliWorkbenchActivity).apply {
            text = "Preparando smali…"
            setTextColor(Color.WHITE)
            maxLines = 2
        }
        bar.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(button("REBUILD") { confirmRebuild() })
        addView(bar, LinearLayout.LayoutParams(-1, dp(54)))

        search = EditText(this@SmaliWorkbenchActivity).apply {
            hint = "Buscar classe .smali"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(105, 118, 135))
            setBackgroundColor(Color.rgb(8, 11, 16))
            setPadding(dp(12), 0, dp(12), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = filter()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        addView(search, LinearLayout.LayoutParams(-1, dp(50)))
        list = ListView(this@SmaliWorkbenchActivity).apply {
            divider = null
            setBackgroundColor(Color.BLACK)
            setOnItemClickListener { _, _, position, _ ->
                val file = shown[position]
                startActivity(Intent(this@SmaliWorkbenchActivity, TextEditorActivity::class.java)
                    .putFileLocation(FileLocation.Direct(file.path), file.name))
            }
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

    private fun disassemble() {
        status.text = "Desmontando $displayName…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    workspace.mkdirs()
                    val backend = graph.resolver.backendFor(source)
                    val node = backend.stat(source)
                    require(node.size in 1..MAX_DEX_BYTES) { "DEX muito grande para o workspace (${node.size / 1024 / 1024} MB)" }
                    backend.openInput(source).use { input -> FileOutputStream(materializedDex).use { input.copyTo(it, 128 * 1024) } }
                    val out = File(workspace, "smali")
                    SmaliEngine.disassemble(materializedDex, out, apiLevel)
                }
            }.onSuccess { count ->
                status.text = "$displayName  •  $count classes"
                refreshFiles()
            }.onFailure { showError(it.message ?: "Falha ao desmontar DEX") }
        }
    }

    private fun refreshFiles() {
        val root = File(workspace, "smali")
        smaliFiles = if (root.isDirectory) root.walkTopDown()
            .filter { it.isFile && it.extension.equals("smali", true) }
            .sortedBy { it.relativeTo(root).path.lowercase() }
            .take(MAX_CLASSES)
            .toList() else emptyList()
        filter()
    }

    private fun filter() {
        if (!::list.isInitialized) return
        val q = search.text?.toString().orEmpty()
        val root = File(workspace, "smali")
        shown = smaliFiles.asSequence()
            .filter { q.isBlank() || it.relativeTo(root).path.contains(q, ignoreCase = true) }
            .take(MAX_VISIBLE)
            .toList()
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, shown.map { it.relativeTo(root).path })
    }

    private fun confirmRebuild() {
        AlertDialog.Builder(this).setTitle("Reconstruir DEX")
            .setMessage("O Smali será compilado e substituirá o DEX selecionado. A substituição só acontece depois que a compilação terminar com sucesso.")
            .setPositiveButton("Reconstruir") { _, _ -> rebuild() }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun rebuild() {
        status.text = "Compilando smali…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val output = File(workspace, "rebuilt.dex")
                    SmaliEngine.assemble(File(workspace, "smali"), output, apiLevel)
                    val backend = graph.resolver.backendFor(source, write = true)
                    backend.openOutput(source, truncate = true).use { out -> output.inputStream().use { it.copyTo(out, 128 * 1024) }; out.flush() }
                    output.length()
                }
            }.onSuccess { size ->
                status.text = "$displayName  •  reconstruído (${size / 1024} KB)"
                Toast.makeText(this@SmaliWorkbenchActivity, "DEX reconstruído e salvo", Toast.LENGTH_LONG).show()
            }.onFailure { showError(it.message ?: "Falha ao reconstruir") }
        }
    }

    private fun showError(message: String) = AlertDialog.Builder(this)
        .setTitle("Smali / DEX")
        .setMessage(message)
        .setPositiveButton("OK", null).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_DEX_BYTES = 256L * 1024 * 1024
        private const val MAX_CLASSES = 100_000
        private const val MAX_VISIBLE = 8_000
    }
}
