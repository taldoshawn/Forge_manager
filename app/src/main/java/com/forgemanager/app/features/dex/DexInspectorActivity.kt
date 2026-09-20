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
import android.widget.ScrollView
import android.widget.TextView
import com.forgemanager.app.core.file.FileLocation
import com.forgemanager.app.core.file.putFileLocation
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
    private lateinit var path: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        path = intent.getStringExtra("path") ?: run { finish(); return }
        setContentView(buildUi())
        load()
    }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)
        val bar = LinearLayout(this@DexInspectorActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(5, 8, 12))
            setPadding(dp(4), 0, dp(4), 0)
        }
        bar.addView(button("←") { finish() })
        bar.addView(TextView(this@DexInspectorActivity).apply {
            text = File(path).name
            setTextColor(Color.WHITE)
            textSize = 16f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(button("SMALI") {
            startActivity(Intent(this@DexInspectorActivity, SmaliWorkbenchActivity::class.java)
                .putFileLocation(FileLocation.Direct(path), File(path).name))
        })
        addView(bar, LinearLayout.LayoutParams(-1, dp(54)))

        val search = EditText(this@DexInspectorActivity).apply {
            hint = "Buscar classe, método, campo, string ou /regex"
            setSingleLine()
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(100, 116, 139))
            setBackgroundColor(Color.rgb(8, 11, 16))
            setPadding(dp(12), 0, dp(12), 0)
            setOnEditorActionListener { _, _, _ -> runSearch(text.toString()); true }
        }
        addView(search, LinearLayout.LayoutParams(-1, dp(50)))
        output = TextView(this@DexInspectorActivity).apply {
            setPadding(dp(14), dp(12), dp(14), dp(24))
            setTextIsSelectable(true)
            setTextColor(Color.rgb(220, 228, 238))
            textSize = 13f
        }
        addView(ScrollView(this@DexInspectorActivity).apply {
            setBackgroundColor(Color.BLACK)
            addView(output)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        minWidth = dp(46)
        setOnClickListener { action() }
    }

    private fun load() {
        output.text = "Analisando DEX…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { DexWorkspace.open(File(path), cacheDir) } }
                .onSuccess { ws ->
                    workspace = ws
                    output.text = ws.dexFiles.joinToString("\n\n") {
                        "${it.name}\nClasses: ${it.classes.size}\nMétodos: ${it.methods.size}\nCampos: ${it.fields.size}\nStrings: ${it.strings.size}\n\n" +
                            it.classes.take(200).joinToString("\n")
                    }
                }.onFailure { showError(it.message ?: "DEX inválido") }
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
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
