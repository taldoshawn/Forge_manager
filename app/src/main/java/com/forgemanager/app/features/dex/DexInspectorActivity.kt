package com.forgemanager.app.features.dex

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DexInspectorActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var workspace: DexWorkspace? = null
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val search = EditText(this).apply { hint = "Buscar classe, método, campo ou string"; setSingleLine() }
        output = TextView(this).apply { setPadding(20,20,20,20); setTextIsSelectable(true) }
        root.addView(search)
        root.addView(android.widget.ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        search.setOnEditorActionListener { _, _, _ -> runSearch(search.text.toString()); true }
        val path = intent.getStringExtra("path") ?: run { finish(); return }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { DexWorkspace.open(File(path), cacheDir) } }
                .onSuccess { ws -> workspace = ws; output.text = ws.dexFiles.joinToString("\n\n") {
                    "${it.name}\nClasses: ${it.classes.size}\nMétodos: ${it.methods.size}\nCampos: ${it.fields.size}\nStrings: ${it.strings.size}\n\n" + it.classes.take(200).joinToString("\n")
                }}.onFailure { showError(it.message ?: "DEX inválido") }
        }
    }

    private fun runSearch(query: String) {
        val ws = workspace ?: return
        runCatching { ws.search(if (query.startsWith('/')) query.drop(1) else query, query.startsWith('/')) }
            .onSuccess { output.text = it.joinToString("\n").ifEmpty { "Nenhum resultado" } }
            .onFailure { showError("Regex inválida") }
    }

    override fun onDestroy() { workspace?.close(); scope.cancel(); super.onDestroy() }
    private fun showError(message: String) = AlertDialog.Builder(this).setTitle("DEX").setMessage(message).setPositiveButton("OK", null).show()
}
