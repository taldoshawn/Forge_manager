package com.forgemanager.app.features.resources

import android.app.AlertDialog
import android.graphics.Color
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
import com.forgemanager.app.core.file.readFileLocation
import com.forgemanager.app.core.ui.ForgeActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class BinaryResourceEditorActivity : ForgeActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }

    private lateinit var location: FileLocation
    private lateinit var displayName: String
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var bytes: ByteArray
    private lateinit var report: AndroidBinaryResources.Report

    private var entries: List<BinaryStringPools.Entry> = emptyList()
    private var shown: List<BinaryStringPools.Entry> = emptyList()
    private var dirty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        location = intent.readFileLocation() ?: run { finish(); return }
        displayName = intent.fileDisplayName()
            ?: location.displayPath.substringAfterLast('/').substringAfterLast("!/").ifBlank { "recurso" }
        setContentView(buildUi())
        load()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @Deprecated("Android back compatibility")
    override fun onBackPressed() {
        if (!dirty) {
            super.onBackPressed()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Alterações não salvas")
            .setMessage("Salvar as alterações em $displayName?")
            .setPositiveButton("Salvar") { _, _ -> save { finish() } }
            .setNegativeButton("Descartar") { _, _ -> finish() }
            .setNeutralButton("Cancelar", null)
            .show()
    }

    private fun buildUi() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(16, 17, 20))

        val bar = LinearLayout(this@BinaryResourceEditorActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(23, 24, 28))
        }
        bar.addView(button("←") { onBackPressed() })
        status = TextView(this@BinaryResourceEditorActivity).apply {
            setTextColor(Color.WHITE)
            text = displayName
            maxLines = 2
            setPadding(dp(6), 0, dp(6), 0)
        }
        bar.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(button("≡") { showStructure() }.apply { contentDescription = "Estrutura binária" })
        bar.addView(button("↻") { load() }.apply { contentDescription = "Recarregar" })
        bar.addView(button("✓") { save() }.apply { contentDescription = "Salvar" })
        addView(bar, LinearLayout.LayoutParams(-1, dp(54)))

        search = EditText(this@BinaryResourceEditorActivity).apply {
            hint = "Filtrar string pool do AXML/ARSC"
            setSingleLine()
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.rgb(29, 31, 36))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = filter()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        addView(search, LinearLayout.LayoutParams(-1, dp(48)))

        list = ListView(this@BinaryResourceEditorActivity).apply {
            setBackgroundColor(Color.rgb(16, 17, 20))
            divider = null
            setOnItemClickListener { _, _, position, _ -> shown.getOrNull(position)?.let(::editEntry) }
        }
        addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        minWidth = dp(44)
        setOnClickListener { action() }
    }

    private fun load() {
        if (dirty) {
            AlertDialog.Builder(this)
                .setTitle("Descartar alterações?")
                .setMessage("Recarregar irá descartar alterações ainda não salvas.")
                .setPositiveButton("Recarregar") { _, _ -> dirty = false; loadNow() }
                .setNegativeButton("Cancelar", null)
                .show()
        } else loadNow()
    }

    private fun loadNow() {
        status.text = "Lendo $displayName…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val backend = graph.resolver.backendFor(location)
                    val node = backend.stat(location)
                    require(node.size in 1..MAX_BYTES) { "AXML/ARSC vazio ou grande demais" }
                    val output = ByteArrayOutputStream(node.size.coerceAtMost(2L * 1024 * 1024).toInt())
                    backend.openInput(location).use { it.copyTo(output, 64 * 1024) }
                    val data = output.toByteArray()
                    val structural = AndroidBinaryResources.inspect(data)
                    Triple(data, structural, BinaryStringPools.find(data))
                }
            }.onSuccess { (data, structural, strings) ->
                bytes = data
                report = structural
                entries = strings
                dirty = false
                filter()
                val type = if (structural.kind == AndroidBinaryResources.Kind.BINARY_XML) "AXML" else "ARSC"
                status.text = "$displayName • $type • ${strings.size} strings • ${structural.chunks.size} chunks"
            }.onFailure { error(it.message ?: "Falha ao abrir recurso Android binário") }
        }
    }

    private fun filter() {
        if (!::bytes.isInitialized) return
        val query = search.text?.toString().orEmpty()
        shown = if (query.isBlank()) entries else entries.filter { it.value.contains(query, ignoreCase = true) }
        list.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            shown.map { "[${it.index}] ${if (it.utf8) "UTF-8" else "UTF-16"}  ${it.value}" }
        )
    }

    private fun editEntry(entry: BinaryStringPools.Entry) {
        val input = EditText(this).apply {
            setText(entry.value)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("String #${entry.index}")
            .setMessage("Alteração binária conservadora: a string só pode ocupar o espaço já reservado; offsets e chunks não são deslocados.")
            .setView(input)
            .setPositiveButton("Aplicar") { _, _ ->
                runCatching {
                    BinaryStringPools.replace(bytes, entry, input.text.toString())
                    AndroidBinaryResources.inspect(bytes)
                }.onSuccess {
                    dirty = true
                    entries = BinaryStringPools.find(bytes)
                    filter()
                    status.text = "$displayName • modificado"
                }.onFailure { error(it.message ?: "A alteração não cabe com segurança no string pool") }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showStructure() {
        if (!::report.isInitialized) return
        AlertDialog.Builder(this)
            .setTitle("Estrutura de $displayName")
            .setMessage(AndroidBinaryResources.describe(report).take(MAX_STRUCTURE_TEXT))
            .setPositiveButton("OK", null)
            .show()
    }

    private fun save(after: (() -> Unit)? = null) {
        if (!::bytes.isInitialized) return
        status.text = "Validando $displayName…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    report = AndroidBinaryResources.inspect(bytes)
                    writeSafely(bytes)
                }
            }.onSuccess {
                dirty = false
                status.text = "$displayName • salvo e validado"
                Toast.makeText(this@BinaryResourceEditorActivity, "Recurso binário salvo", Toast.LENGTH_SHORT).show()
                after?.invoke()
            }.onFailure { error(it.message ?: "Falha ao salvar recurso binário") }
        }
    }

    private suspend fun writeSafely(data: ByteArray) {
        val backend = graph.resolver.backendFor(location, write = true)
        val direct = location as? FileLocation.Direct
        if (direct != null && backend.id == "direct") {
            val target = File(direct.path)
            val parent = target.parentFile ?: error("Pasta de destino inválida")
            val temp = File(parent, ".${target.name}.${System.nanoTime()}.forge.tmp")
            try {
                FileOutputStream(temp).use { output ->
                    output.write(data)
                    output.flush()
                    output.fd.sync()
                }
                val oldMode = runCatching { android.system.Os.stat(target.path).st_mode and 0xFFF }.getOrNull()
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
            output.write(data)
            output.flush()
        }
    }

    private fun error(message: String) = AlertDialog.Builder(this)
        .setTitle("Editor AXML/ARSC")
        .setMessage(message)
        .setPositiveButton("OK", null)
        .show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_BYTES = 128L * 1024 * 1024
        private const val MAX_STRUCTURE_TEXT = 120_000
    }
}
