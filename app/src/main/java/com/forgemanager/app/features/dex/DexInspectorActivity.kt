package com.forgemanager.app.features.dex

import com.forgemanager.app.core.ui.ForgeActivity

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import com.forgemanager.app.core.file.FileLocation
import com.forgemanager.app.core.file.putFileLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DexInspectorActivity : ForgeActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var workspace: DexWorkspace? = null
    private lateinit var output: TextView
    private lateinit var summary: TextView
    private lateinit var search: EditText
    private lateinit var source: File
    private var currentText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path") ?: run { finish(); return }
        source = File(path)
        setContentView(buildUi())
        load()
    }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)

        val titleBar = LinearLayout(this@DexInspectorActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            addView(button("←") { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(TextView(this@DexInspectorActivity).apply {
                text = "DEX Inspector"
                textSize = 19f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("SMALI") { openSmali() })
        }
        addView(titleBar, LinearLayout.LayoutParams(-1, dp(58)))

        summary = TextView(this@DexInspectorActivity).apply {
            setTextColor(Color.rgb(112, 188, 255))
            textSize = 11f
            setPadding(dp(14), dp(4), dp(14), dp(8))
            text = "Carregando…"
        }
        addView(summary)

        search = EditText(this@DexInspectorActivity).apply {
            hint = "Buscar classe, método, campo ou string; /regex para regex"
            setHintTextColor(Color.rgb(115, 124, 139))
            setTextColor(Color.WHITE)
            setSingleLine()
            setPadding(dp(14), 0, dp(14), 0)
            setOnEditorActionListener { _, _, _ -> runSearch(text.toString()); true }
        }
        addView(search, LinearLayout.LayoutParams(-1, dp(48)))

        val actions = LinearLayout(this@DexInspectorActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), dp(5))
            addView(button("CLASSES") { showCategory(Category.CLASSES) })
            addView(button("MÉTODOS") { showCategory(Category.METHODS) })
            addView(button("CAMPOS") { showCategory(Category.FIELDS) })
            addView(button("STRINGS") { showCategory(Category.STRINGS) })
            addView(button("COPIAR") { copyResults() })
            addView(button("EXPORTAR") { exportReport() })
        }
        addView(HorizontalScrollView(this@DexInspectorActivity).apply { addView(actions) }, LinearLayout.LayoutParams(-1, dp(52)))

        output = TextView(this@DexInspectorActivity).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.rgb(222, 229, 239))
            setTextIsSelectable(true)
            setPadding(dp(14), dp(8), dp(14), dp(30))
        }
        addView(ScrollView(this@DexInspectorActivity).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun load() {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { DexWorkspace.open(source, cacheDir) } }
                .onSuccess { ws ->
                    workspace = ws
                    summary.text = buildString {
                        append(source.name).append("  •  DEX: ").append(ws.dexFiles.size)
                        append("  •  Classes: ").append(ws.dexFiles.sumOf { it.classes.size })
                        append("  •  Métodos: ").append(ws.dexFiles.sumOf { it.methods.size })
                        append("  •  Campos: ").append(ws.dexFiles.sumOf { it.fields.size })
                        append("  •  Strings: ").append(ws.dexFiles.sumOf { it.strings.size })
                    }
                    showCategory(Category.CLASSES)
                }
                .onFailure { showError(it.message ?: "DEX inválido") }
        }
    }

    private fun showCategory(category: Category) {
        val ws = workspace ?: return
        currentText = buildString {
            ws.dexFiles.forEach { dex ->
                append("══ ").append(dex.name).append(" ══\n")
                val values = when (category) {
                    Category.CLASSES -> dex.classes
                    Category.METHODS -> dex.methods
                    Category.FIELDS -> dex.fields
                    Category.STRINGS -> dex.strings
                }
                values.take(MAX_VISIBLE).forEach { append(it).append('\n') }
                if (values.size > MAX_VISIBLE) append("… ").append(values.size - MAX_VISIBLE).append(" itens não exibidos\n")
                append('\n')
            }
        }
        output.text = currentText.ifBlank { "Nenhum item" }
    }

    private fun runSearch(queryRaw: String) {
        val ws = workspace ?: return
        val query = queryRaw.trim()
        if (query.isEmpty()) { showCategory(Category.CLASSES); return }
        val regex = query.startsWith('/')
        val body = if (regex) query.drop(1) else query
        runCatching { ws.search(body, regex) }
            .onSuccess {
                currentText = it.take(MAX_VISIBLE).joinToString("\n")
                if (it.size > MAX_VISIBLE) currentText += "\n… ${it.size - MAX_VISIBLE} resultados não exibidos"
                output.text = currentText.ifBlank { "Nenhum resultado" }
            }
            .onFailure { showError("Regex inválida") }
    }

    private fun openSmali() {
        if (!::source.isInitialized || !source.isFile) return
        startActivity(
            Intent(this, SmaliStudioActivity::class.java)
                .putFileLocation(FileLocation.Direct(source.path), source.name)
        )
    }

    private fun copyResults() {
        val value = currentText.ifBlank { output.text.toString() }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("DEX", value))
        android.widget.Toast.makeText(this, "Resultado copiado", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun exportReport() {
        val ws = workspace ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val report = File(cacheDir, "dex-report-${System.nanoTime()}.txt")
                    report.bufferedWriter().use { writer ->
                        writer.appendLine("Forge Manager — DEX report")
                        writer.appendLine("Source: ${source.path}")
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
                            writer.appendLine("\n[Strings]")
                            dex.strings.forEach(writer::appendLine)
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

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 10f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        minWidth = dp(54)
        setOnClickListener { action() }
    }

    override fun onDestroy() {
        workspace?.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun showError(message: String) = AlertDialog.Builder(this)
        .setTitle("DEX Inspector")
        .setMessage(message)
        .setPositiveButton("OK", null)
        .show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private enum class Category { CLASSES, METHODS, FIELDS, STRINGS }

    companion object { private const val MAX_VISIBLE = 5_000 }
}
