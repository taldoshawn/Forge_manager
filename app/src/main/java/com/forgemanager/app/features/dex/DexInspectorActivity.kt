package com.forgemanager.app.features.dex

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
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
    private lateinit var sourcePath: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sourcePath = intent.getStringExtra("path") ?: run { finish(); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.BLACK) }
        bar.addView(Button(this).apply { text = "←"; setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { finish() } })
        bar.addView(TextView(this).apply { text = "DEX Inspector"; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(Button(this).apply {
            text = "SMALI"
            setTextColor(Color.rgb(0, 190, 255))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { startActivity(Intent(this@DexInspectorActivity, SmaliWorkspaceActivity::class.java).putExtra(SmaliWorkspaceActivity.EXTRA_PATH, sourcePath)) }
        })
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(52)))
        val search = EditText(this).apply {
            hint = "Buscar classe, método, campo ou string"
            setSingleLine()
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.rgb(8, 8, 8))
        }
        output = TextView(this).apply { setPadding(20,20,20,20); setTextIsSelectable(true); setTextColor(Color.LTGRAY) }
        root.addView(search)
        root.addView(android.widget.ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        search.setOnEditorActionListener { _, _, _ -> runSearch(search.text.toString()); true }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { DexWorkspace.open(File(sourcePath), cacheDir) } }
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
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
