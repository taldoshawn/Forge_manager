package com.forgemanager.app.features.apk

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.forgemanager.app.core.file.FileLocation
import com.forgemanager.app.core.file.putFileLocation
import com.forgemanager.app.features.dex.SmaliWorkbenchActivity
import com.forgemanager.app.features.editor.BinaryResourceEditorActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

class ApkToolsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var apk: File
    private lateinit var output: TextView
    private lateinit var title: TextView
    private var lastProduced: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        apk = File(path)
        if (!apk.isFile) { finish(); return }
        setContentView(buildUi())
        verify()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)
        val bar = LinearLayout(this@ApkToolsActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(6, 9, 13))
            setPadding(dp(4), 0, dp(4), 0)
        }
        bar.addView(button("←") { finish() })
        title = TextView(this@ApkToolsActivity).apply {
            text = apk.name
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 2
        }
        bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(button("⋮") { showOutputActions() })
        addView(bar, LinearLayout.LayoutParams(-1, dp(54)))

        val actions = LinearLayout(this@ApkToolsActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.rgb(3, 5, 8))
        }
        actions.addView(wideButton("Verificar assinaturas") { verify() })
        actions.addView(wideButton("Smali / DEX") { chooseDex() })
        actions.addView(wideButton("AndroidManifest.xml (AXML)") { openBinary("AndroidManifest.xml") })
        actions.addView(wideButton("resources.arsc") { openBinary("resources.arsc") })
        actions.addView(wideButton("Zipalign 4 bytes") { zipalign() })
        actions.addView(wideButton("Zipalign + assinar v1/v2/v3/v4") { signAll() })
        addView(actions, LinearLayout.LayoutParams(-1, -2))

        output = TextView(this@ApkToolsActivity).apply {
            setTextColor(Color.rgb(220, 228, 238))
            setTextIsSelectable(true)
            textSize = 13f
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }
        addView(ScrollView(this@ApkToolsActivity).apply { addView(output); setBackgroundColor(Color.BLACK) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        minWidth = dp(48)
        setOnClickListener { action() }
    }

    private fun wideButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(10, 16, 24))
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { action() }
    }

    private fun verify() {
        output.text = "Verificando ${apk.name}…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { ApkSigningService.verify(apk) } }
                .onSuccess { output.text = "${apk.name}\n\n$it" }
                .onFailure { output.text = "Não foi possível verificar:\n${it.message}" }
        }
    }

    private fun chooseDex() {
        scope.launch {
            val entries = runCatching { withContext(Dispatchers.IO) {
                ZipFile(apk).use { zip -> zip.entries().asSequence().map { it.name }
                    .filter { Regex("^classes(?:\\d+)?\\.dex$").matches(it) }.toList() }
            }}.getOrElse { showError(it.message ?: "Falha ao ler APK"); return@launch }
            if (entries.isEmpty()) { showError("Nenhum classes*.dex encontrado"); return@launch }
            AlertDialog.Builder(this@ApkToolsActivity).setTitle("Escolha o DEX")
                .setItems(entries.toTypedArray()) { _, which ->
                    val entry = entries[which]
                    startActivity(Intent(this@ApkToolsActivity, SmaliWorkbenchActivity::class.java)
                        .putFileLocation(FileLocation.Archive(apk.path, entry), entry))
                }.show()
        }
    }

    private fun openBinary(entryName: String) {
        scope.launch {
            val exists = runCatching { withContext(Dispatchers.IO) { ZipFile(apk).use { it.getEntry(entryName) != null } } }.getOrDefault(false)
            if (!exists) { showError("$entryName não existe neste APK"); return@launch }
            startActivity(Intent(this@ApkToolsActivity, BinaryResourceEditorActivity::class.java)
                .putFileLocation(FileLocation.Archive(apk.path, entryName), entryName))
        }
    }

    private fun zipalign() {
        val outputFile = sibling("aligned")
        output.text = "Executando zipalign…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { ApkZipAligner.align(apk, outputFile) } }
                .onSuccess {
                    lastProduced = it
                    output.text = "Zipalign concluído.\n\n${it.path}\n${it.length()} bytes\n\nA assinatura anterior foi removida pela reescrita. Assine o APK antes de instalar."
                    Toast.makeText(this@ApkToolsActivity, "APK alinhado", Toast.LENGTH_SHORT).show()
                }.onFailure { showError(it.message ?: "Zipalign falhou") }
        }
    }

    private fun signAll() {
        AlertDialog.Builder(this).setTitle("Assinar APK")
            .setMessage("O Forge Manager vai criar uma cópia alinhada e assiná-la com uma chave RSA protegida pelo AndroidKeyStore deste aparelho. Serão geradas assinaturas v1, v2, v3 e v4; a v4 fica também em um arquivo .idsig separado.")
            .setPositiveButton("Assinar") { _, _ -> doSign() }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun doSign() {
        output.text = "Alinhando e assinando…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val tempAligned = File(cacheDir, "apk-tools/${System.nanoTime()}-aligned.apk").apply { parentFile?.mkdirs() }
                ApkZipAligner.align(apk, tempAligned)
                val signed = sibling("signed")
                val result = ApkSigningService.sign(this@ApkToolsActivity, tempAligned, signed)
                tempAligned.delete()
                result
            }}.onSuccess { result ->
                lastProduced = result.apk
                output.text = buildString {
                    appendLine("APK assinado: ${result.apk.path}")
                    appendLine("V4/idsig: ${result.idsig.path}")
                    appendLine()
                    appendLine("Verificado: ${result.verified}")
                    appendLine("v1: ${result.v1}")
                    appendLine("v2: ${result.v2}")
                    appendLine("v3: ${result.v3}")
                    appendLine("v4: ${result.v4}")
                    appendLine()
                    appendLine("SHA-256 do certificado:")
                    appendLine(result.certificateSha256)
                }
                Toast.makeText(this@ApkToolsActivity, "APK assinado e verificado", Toast.LENGTH_LONG).show()
            }.onFailure { showError(it.message ?: "Assinatura falhou") }
        }
    }

    private fun sibling(suffix: String): File {
        val base = apk.name.removeSuffix(".apk")
        var candidate = File(apk.parentFile, "$base-$suffix.apk")
        var n = 2
        while (candidate.exists()) candidate = File(apk.parentFile, "$base-$suffix-$n.apk").also { n++ }
        return candidate
    }

    private fun showOutputActions() {
        val file = lastProduced
        val options = if (file?.isFile == true) arrayOf("Compartilhar último APK", "Instalar último APK", "Verificar original") else arrayOf("Verificar original")
        AlertDialog.Builder(this).setTitle("APK Tools").setItems(options) { _, which ->
            when (options[which]) {
                "Compartilhar último APK" -> file?.let(::share)
                "Instalar último APK" -> file?.let(::install)
                else -> verify()
            }
        }.show()
    }

    private fun share(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
            .setType("application/vnd.android.package-archive")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Compartilhar APK"))
    }

    private fun install(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun showError(message: String) = AlertDialog.Builder(this).setTitle("APK Tools")
        .setMessage(message).setPositiveButton("OK", null).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object { const val EXTRA_PATH = "path" }
}
