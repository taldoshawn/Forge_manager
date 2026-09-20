package com.forgemanager.app.features.resources

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
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
import com.reandroid.arsc.model.ResourceEntry
import com.reandroid.arsc.value.ValueType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ResourceTableActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }
    private lateinit var apk: File
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var search: EditText
    private var workspace: AndroidResourceWorkspace? = null
    private var allRows: List<Row> = emptyList()
    private var visibleRows: List<Row> = emptyList()
    private var dirty = false

    private data class Row(val resource: ResourceEntry) {
        fun value(): String? = resource.get()?.getResValue()?.getValueAsString()
        fun label(): String = buildString {
            append(resource.getHexId()).append("  ")
            append(resource.getType()).append('/').append(resource.getName() ?: "?")
            append("  [").append(resource.getConfigsCount()).append("]")
            value()?.let { append("\n").append(it.take(180)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apk = File(intent.getStringExtra(EXTRA_APK_PATH) ?: run { finish(); return })
        setContentView(buildUi())
        load()
    }

    override fun onDestroy() {
        workspace?.close()
        scope.cancel()
        super.onDestroy()
    }

    @Deprecated("Android back compatibility")
    override fun onBackPressed() {
        if (dirty) {
            AlertDialog.Builder(this).setTitle("Recursos alterados")
                .setMessage("Salvar resources.arsc antes de sair?")
                .setPositiveButton("Salvar") { _, _ -> save { finish() } }
                .setNegativeButton("Descartar") { _, _ -> finish() }
                .setNeutralButton("Cancelar", null).show()
        } else super.onBackPressed()
    }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)
        val bar = LinearLayout(this@ResourceTableActivity).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.BLACK) }
        bar.addView(button("←") { onBackPressed() })
        status = TextView(this@ResourceTableActivity).apply { text = "resources.arsc"; setTextColor(Color.WHITE); maxLines = 2 }
        bar.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(button("SAVE") { save() })
        addView(bar, LinearLayout.LayoutParams(-1, dp(54)))

        search = EditText(this@ResourceTableActivity).apply {
            hint = "Buscar id, tipo, nome ou valor"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.rgb(8, 8, 8))
            addTextChangedListener(ResourceWatcher { filter(it) })
        }
        addView(search, LinearLayout.LayoutParams(-1, dp(50)))

        list = ListView(this@ResourceTableActivity).apply {
            setBackgroundColor(Color.BLACK)
            setOnItemClickListener { _, _, position, _ -> showResource(visibleRows[position]) }
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

    private fun load() {
        status.text = "Lendo resources.arsc…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val ws = AndroidResourceWorkspace.open(apk, cacheDir)
                    val rows = ArrayList<Row>()
                    val iterator = ws.table.getResources()
                    while (iterator.hasNext() && rows.size < MAX_RESOURCES) rows += Row(iterator.next())
                    ws to rows.sortedWith(compareBy<Row> { it.resource.getType().orEmpty() }.thenBy { it.resource.getName().orEmpty() })
                }
            }.onSuccess { (ws, rows) ->
                workspace = ws
                allRows = rows
                status.text = "resources.arsc • ${rows.size} recursos"
                filter(search.text.toString())
            }.onFailure { showError(it.message ?: "Falha ao ler resources.arsc") }
        }
    }

    private fun filter(query: String) {
        val q = query.trim()
        visibleRows = if (q.isBlank()) allRows else allRows.filter { row ->
            val r = row.resource
            r.getHexId().contains(q, true) || r.getType().orEmpty().contains(q, true) ||
                r.getName().orEmpty().contains(q, true) || row.value().orEmpty().contains(q, true)
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, visibleRows.map { it.label() })
    }

    private fun showResource(row: Row) {
        val entry = row.resource.get()
        val value = entry?.getResValue()
        val current = value?.getValueAsString()
        val info = "ID: ${row.resource.getHexId()}\nNome: ${row.resource.buildReference()}\nConfigurações: ${row.resource.getConfigsCount()}\nTipo do valor: ${value?.getValueType() ?: "complexo/bag"}"
        if (value == null || value.getValueType() != ValueType.STRING) {
            AlertDialog.Builder(this).setTitle("Recurso").setMessage(info).setPositiveButton("OK", null).show()
            return
        }
        val input = EditText(this).apply { setText(current.orEmpty()); setTextColor(Color.WHITE); setSelectAllOnFocus(false) }
        AlertDialog.Builder(this).setTitle(row.resource.buildReference()).setMessage(info).setView(input)
            .setPositiveButton("Aplicar") { _, _ ->
                value.setValueAsString(input.text.toString())
                dirty = true
                status.text = "resources.arsc • alterações não salvas"
                filter(search.text.toString())
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun save(after: (() -> Unit)? = null) {
        val ws = workspace ?: return
        if (!dirty) { after?.invoke(); return }
        AlertDialog.Builder(this).setTitle("Salvar resources.arsc")
            .setMessage("A tabela recompilada substituirá resources.arsc dentro do APK e invalidará a assinatura atual. Faça uma cópia ou assine o APK novamente depois.")
            .setPositiveButton("Salvar") { _, _ ->
                status.text = "Recompilando resources.arsc…"
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val temp = File(ws.directory, "resources-edited-${System.nanoTime()}.arsc")
                            ws.saveTable(temp)
                            val location = FileLocation.Archive(apk.path, "resources.arsc")
                            val backend = graph.resolver.backendFor(location, write = true)
                            backend.openOutput(location, true).use { output -> temp.inputStream().buffered().use { it.copyTo(output, 128 * 1024) } }
                            temp.delete()
                        }
                    }.onSuccess {
                        dirty = false
                        status.text = "resources.arsc • salvo • APK precisa ser reassinado"
                        Toast.makeText(this@ResourceTableActivity, "Tabela de recursos salva", Toast.LENGTH_LONG).show()
                        after?.invoke()
                    }.onFailure { showError(it.message ?: "Falha ao salvar resources.arsc") }
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun showError(message: String) = AlertDialog.Builder(this)
        .setTitle("Editor ARSC").setMessage(message).setPositiveButton("OK", null).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_APK_PATH = "apk_path"
        private const val MAX_RESOURCES = 500_000
    }
}

private class ResourceWatcher(private val changed: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    override fun afterTextChanged(s: android.text.Editable?) = changed(s?.toString().orEmpty())
}
