package com.forgemanager.app.features.resources

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.forgemanager.app.ForgeApplication
import com.forgemanager.app.core.file.FileLocation
import com.forgemanager.app.features.editor.SyntaxHighlighter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class BinaryXmlEditorActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }
    private lateinit var apk: File
    private lateinit var entryName: String
    private lateinit var editor: EditText
    private lateinit var status: TextView
    private var workspace: AndroidResourceWorkspace? = null
    private var original = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apk = File(intent.getStringExtra(EXTRA_APK_PATH) ?: run { finish(); return })
        entryName = intent.getStringExtra(EXTRA_ENTRY) ?: "AndroidManifest.xml"
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
        if (::editor.isInitialized && editor.text.toString() != original) {
            AlertDialog.Builder(this).setTitle("Alterações não salvas")
                .setMessage("Salvar $entryName antes de sair?")
                .setPositiveButton("Salvar") { _, _ -> save { finish() } }
                .setNegativeButton("Descartar") { _, _ -> finish() }
                .setNeutralButton("Cancelar", null).show()
        } else super.onBackPressed()
    }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)
        val bar = LinearLayout(this@BinaryXmlEditorActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        bar.addView(button("←") { onBackPressed() })
        status = TextView(this@BinaryXmlEditorActivity).apply {
            text = entryName
            setTextColor(Color.WHITE)
            maxLines = 2
        }
        bar.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(button("SAVE") { save() })
        addView(bar, LinearLayout.LayoutParams(-1, dp(54)))

        editor = EditText(this@BinaryXmlEditorActivity).apply {
            gravity = Gravity.TOP or Gravity.START
            typeface = Typeface.MONOSPACE
            textSize = 12.5f
            setTextColor(Color.rgb(225, 232, 240))
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.BLACK)
            setPadding(dp(10), dp(10), dp(10), dp(20))
            setHorizontallyScrolling(true)
        }
        addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11f
        setTextColor(Color.rgb(0, 190, 255))
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { action() }
    }

    private fun load() {
        status.text = "Decodificando $entryName…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val ws = AndroidResourceWorkspace.open(apk, cacheDir)
                    val text = ws.decodeBinaryXml(entryName)
                    ws to text
                }
            }.onSuccess { (ws, text) ->
                workspace = ws
                original = text
                editor.setText(text)
                editor.setSelection(0)
                SyntaxHighlighter.apply(editor.text, "file.xml")
                status.text = "$entryName • AXML"
            }.onFailure { showError(it.message ?: "Falha ao decodificar AXML") }
        }
    }

    private fun save(after: (() -> Unit)? = null) {
        val ws = workspace ?: return
        val xml = editor.text.toString()
        AlertDialog.Builder(this).setTitle("Salvar AXML")
            .setMessage("O XML será recompilado para o formato binário Android e substituirá $entryName dentro do APK. A assinatura atual do APK será invalidada.")
            .setPositiveButton("Salvar") { _, _ ->
                status.text = "Compilando AXML…"
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val temp = File(ws.directory, "edited-${System.nanoTime()}.xml.bin")
                            ws.encodeBinaryXml(entryName, xml, temp)
                            val location = FileLocation.Archive(apk.path, entryName)
                            val backend = graph.resolver.backendFor(location, write = true)
                            backend.openOutput(location, true).use { output -> temp.inputStream().buffered().use { it.copyTo(output, 128 * 1024) } }
                            temp.delete()
                        }
                    }.onSuccess {
                        original = xml
                        status.text = "$entryName • salvo • APK precisa ser reassinado"
                        Toast.makeText(this@BinaryXmlEditorActivity, "AXML salvo", Toast.LENGTH_SHORT).show()
                        after?.invoke()
                    }.onFailure { showError(it.message ?: "Falha ao salvar AXML") }
                }
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun showError(message: String) = AlertDialog.Builder(this)
        .setTitle("Editor AXML").setMessage(message).setPositiveButton("OK", null).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_APK_PATH = "apk_path"
        const val EXTRA_ENTRY = "entry_name"
    }
}
