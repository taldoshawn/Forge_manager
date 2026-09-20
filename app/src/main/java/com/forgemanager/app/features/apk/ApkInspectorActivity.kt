package com.forgemanager.app.features.apk

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.forgemanager.app.features.dex.SmaliWorkspaceActivity
import com.forgemanager.app.features.explorer.FileListAdapter
import com.forgemanager.app.features.resources.BinaryXmlEditorActivity
import com.forgemanager.app.features.resources.ResourceTableActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

class ApkInspectorActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var file: File
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        file = File(intent.getStringExtra("path") ?: run { finish(); return })
        setContentView(buildUi())
        inspect()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)
        val bar = LinearLayout(this@ApkInspectorActivity).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.BLACK) }
        bar.addView(tool("←") { finish() })
        bar.addView(TextView(this@ApkInspectorActivity).apply { text = file.name; setTextColor(Color.WHITE); textSize = 16f }, LinearLayout.LayoutParams(0,-2,1f))
        bar.addView(tool("INSTALL") { install() })
        addView(bar, LinearLayout.LayoutParams(-1, dp(52)))

        addView(HorizontalScrollView(this@ApkInspectorActivity).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(this@ApkInspectorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(tool("SMALI") { startActivity(Intent(this@ApkInspectorActivity, SmaliWorkspaceActivity::class.java).putExtra(SmaliWorkspaceActivity.EXTRA_PATH, file.path)) })
                addView(tool("MANIFEST") { startActivity(Intent(this@ApkInspectorActivity, BinaryXmlEditorActivity::class.java)
                    .putExtra(BinaryXmlEditorActivity.EXTRA_APK_PATH, file.path).putExtra(BinaryXmlEditorActivity.EXTRA_ENTRY, "AndroidManifest.xml")) })
                addView(tool("ARSC") { startActivity(Intent(this@ApkInspectorActivity, ResourceTableActivity::class.java).putExtra(ResourceTableActivity.EXTRA_APK_PATH, file.path)) })
                addView(tool("ZIPALIGN") { showZipAlign() })
                addView(tool("SIGN") { startActivity(Intent(this@ApkInspectorActivity, ApkSignerActivity::class.java).putExtra(ApkSignerActivity.EXTRA_APK_PATH, file.path)) })
            })
        }, LinearLayout.LayoutParams(-1, dp(48)))

        output = TextView(this@ApkInspectorActivity).apply {
            setPadding(dp(14),dp(12),dp(14),dp(20)); setTextIsSelectable(true); textSize = 13f
            setTextColor(Color.rgb(205, 214, 224)); setBackgroundColor(Color.BLACK)
        }
        addView(ScrollView(this@ApkInspectorActivity).apply { setBackgroundColor(Color.BLACK); addView(output) }, LinearLayout.LayoutParams(-1,0,1f))
    }

    private fun tool(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 10f
        setTextColor(Color.rgb(0, 190, 255))
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { action() }
    }

    @Suppress("DEPRECATION")
    private fun inspect() {
        val signingFlag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or PackageManager.GET_PERMISSIONS or signingFlag or PackageManager.GET_META_DATA
        val info = if (Build.VERSION.SDK_INT >= 33) packageManager.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else packageManager.getPackageArchiveInfo(file.path, flags)
        if (info == null) { output.text = "APK inválido ou não reconhecido pelo Android."; return }
        info.applicationInfo?.apply { sourceDir = file.path; publicSourceDir = file.path }
        output.text = buildReport(info)
    }

    @Suppress("DEPRECATION")
    private fun buildReport(info: PackageInfo): String = buildString {
        appendLine("PACOTE")
        appendLine(info.packageName)
        appendLine()
        appendLine("VERSÃO")
        appendLine("${info.versionName ?: "—"} (${if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()})")
        appendLine("minSdk: ${info.applicationInfo?.minSdkVersion ?: "—"}")
        appendLine("targetSdk: ${info.applicationInfo?.targetSdkVersion ?: "—"}")
        appendLine("Tamanho: ${FileListAdapter.formatBytes(file.length())}")
        appendLine()
        appendSection("PERMISSÕES", info.requestedPermissions?.asList().orEmpty())
        appendSection("ACTIVITIES", info.activities?.map { it.name }.orEmpty())
        appendSection("SERVICES", info.services?.map { it.name }.orEmpty())
        appendSection("RECEIVERS", info.receivers?.map { it.name }.orEmpty())
        appendSection("PROVIDERS", info.providers?.map { "${it.name}  [${it.authority}]" }.orEmpty())
        appendLine("ASSINATURAS")
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        signatures.orEmpty().forEachIndexed { index, signature ->
            appendLine("#${index + 1} SHA-256: ${sha256(signature.toByteArray())}")
            runCatching {
                val cert = CertificateFactory.getInstance("X.509").generateCertificate(signature.toByteArray().inputStream()) as X509Certificate
                appendLine("Sujeito: ${cert.subjectX500Principal.name}")
                appendLine("Válido: ${cert.notBefore} – ${cert.notAfter}")
            }
        }
        appendLine()
        appendLine("CONTEÚDO")
        runCatching { ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().toList()
            val dex = entries.filter { Regex("classes(\\d*)\\.dex").matches(it.name) }.map { it.name }
            val libs = entries.filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }.map { it.name }
            appendLine("DEX: ${dex.joinToString().ifEmpty { "nenhum" }}")
            appendLine("Manifest: ${entries.any { it.name == "AndroidManifest.xml" }}")
            appendLine("resources.arsc: ${entries.any { it.name == "resources.arsc" }}")
            appendLine("Assets: ${entries.count { it.name.startsWith("assets/") }}")
            appendLine("Bibliotecas nativas: ${libs.size}")
            libs.take(30).forEach { appendLine("• $it") }
        }}.onFailure { appendLine("Falha ao ler ZIP: ${it.message}") }
    }

    private fun StringBuilder.appendSection(title: String, values: List<String>) {
        appendLine(); appendLine(title)
        if (values.isEmpty()) appendLine("—") else values.sorted().forEach { appendLine("• $it") }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(":") { "%02X".format(it) }

    private fun showZipAlign() {
        val defaultOutput = File(file.parentFile, "${file.nameWithoutExtension}-aligned.apk")
        val input = EditText(this).apply { setText(defaultOutput.path); setSingleLine() }
        AlertDialog.Builder(this).setTitle("Zipalign 4-byte").setView(input)
            .setMessage("Cria uma nova cópia alinhada. Reescrever o ZIP invalida assinaturas existentes; assine a saída depois.")
            .setPositiveButton("Alinhar") { _, _ ->
                val destination = File(input.text.toString())
                output.text = "Executando zipalign…\n\n${output.text}"
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { ZipAligner.align(file, destination, 4) } }
                        .onSuccess { result -> Toast.makeText(this@ApkInspectorActivity, "Zipalign concluído: ${result.entries} entradas", Toast.LENGTH_LONG).show() }
                        .onFailure { AlertDialog.Builder(this@ApkInspectorActivity).setTitle("Zipalign").setMessage(it.message ?: "Falha").setPositiveButton("OK", null).show() }
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun install() {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { AlertDialog.Builder(this).setTitle("Instalação").setMessage(it.message ?: "Falha ao abrir instalador").setPositiveButton("OK", null).show() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

private fun <T> java.util.Enumeration<T>.asSequence(): Sequence<T> = sequence { while (hasMoreElements()) yield(nextElement()) }
