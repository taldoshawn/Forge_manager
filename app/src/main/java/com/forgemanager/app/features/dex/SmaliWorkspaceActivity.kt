package com.forgemanager.app.features.dex

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
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
import com.forgemanager.app.core.file.putFileLocation
import com.forgemanager.app.features.editor.TextEditorActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class SmaliWorkspaceActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }
    private lateinit var source: File
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var search: EditText
    private var workspace: SmaliWorkspace? = null
    private var allFiles: List<File> = emptyList()
    private var visibleFiles: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source = File(intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return })
        setContentView(buildUi())
        loadWorkspace()
    }

    override fun onDestroy() {
        workspace?.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)
        val top = LinearLayout(this@SmaliWorkspaceActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        top.addView(button("←") { finish() })
        status = TextView(this@SmaliWorkspaceActivity).apply {
            setTextColor(Color.WHITE)
            text = "Smali • ${source.name}"
            maxLines = 2
        }
        top.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(button("REBUILD") { confirmRebuild() })
        addView(top, LinearLayout.LayoutParams(-1, dp(54)))

        search = EditText(this@SmaliWorkspaceActivity).apply {
            hint = "Buscar classe/caminho Smali"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.rgb(8, 8, 8))
            setPadding(dp(12), 0, dp(12), 0)
            addTextChangedListener(SimpleTextWatcher { filter(it) })
        }
        addView(search, LinearLayout.LayoutParams(-1, dp(50)))

        list = ListView(this@SmaliWorkspaceActivity).apply {
            dividerHeight = 1
            setBackgroundColor(Color.BLACK)
            setOnItemClickListener { _, _, position, _ ->
                val file = visibleFiles[position]
                startActivity(Intent(this@SmaliWorkspaceActivity, TextEditorActivity::class.java)
                    .putFileLocation(FileLocation.Direct(file.path), file.name))
            }
        }
        addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11f
        setTextColor(Color.rgb(0, 190, 255))
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { action() }
    }

    private fun loadWorkspace() {
        status.text = "Desmontando DEX…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { SmaliWorkspace.open(source, cacheDir) } }
                .onSuccess { ws ->
                    workspace = ws
                    allFiles = ws.allSmaliFiles()
                    status.text = "${source.name} • ${ws.dexUnits.size} DEX • ${allFiles.size} classes"
                    filter(search.text.toString())
                }
                .onFailure { showError("Smali", it.message ?: "Falha ao desmontar DEX") }
        }
    }

    private fun filter(query: String) {
        val needle = query.trim()
        visibleFiles = if (needle.isBlank()) allFiles else allFiles.filter {
            it.path.contains(needle, ignoreCase = true)
        }
        val labels = visibleFiles.map { file ->
            val ws = workspace
            if (ws == null) file.name else file.relativeTo(ws.root).path
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
    }

    private fun confirmRebuild() {
        val ws = workspace ?: return
        val message = if (source.extension.equals("dex", true)) {
            "O DEX original será substituído após a reconstrução."
        } else {
            "Os DEX reconstruídos serão gravados dentro de ${source.name}. Isso invalida a assinatura APK atual; assine o APK novamente depois."
        }
        AlertDialog.Builder(this).setTitle("Reconstruir Smali → DEX")
            .setMessage(message)
            .setPositiveButton("Reconstruir") { _, _ -> rebuild(ws) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun rebuild(ws: SmaliWorkspace) {
        status.text = "Reconstruindo DEX…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val rebuilt = ws.rebuild()
                    if (source.extension.equals("dex", true)) {
                        require(rebuilt.size == 1) { "DEX único esperado" }
                        replaceDirectAtomically(rebuilt.single().second, source)
                    } else {
                        for ((entryName, dexFile) in rebuilt) {
                            val location = FileLocation.Archive(source.path, entryName)
                            val backend = graph.resolver.backendFor(location, write = true)
                            backend.openOutput(location, truncate = true).use { output ->
                                dexFile.inputStream().buffered().use { input -> input.copyTo(output, 128 * 1024) }
                            }
                        }
                    }
                    rebuilt.size
                }
            }.onSuccess { count ->
                status.text = "Concluído • $count DEX reconstruído(s)"
                Toast.makeText(this@SmaliWorkspaceActivity, "DEX reconstruído com sucesso", Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                status.text = "Falha na reconstrução"
                showError("Rebuild Smali", error.message ?: "Falha")
            }
        }
    }

    private fun replaceDirectAtomically(sourceFile: File, destination: File) {
        val parent = destination.parentFile ?: error("Destino inválido")
        val temp = File(parent, ".forge-dex-${System.nanoTime()}.tmp")
        sourceFile.copyTo(temp, overwrite = true)
        try {
            runCatching {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    private fun showError(title: String, message: String) = AlertDialog.Builder(this)
        .setTitle(title).setMessage(message).setPositiveButton("OK", null).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object { const val EXTRA_PATH = "path" }
}

private class SimpleTextWatcher(private val changed: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    override fun afterTextChanged(s: android.text.Editable?) = changed(s?.toString().orEmpty())
}
