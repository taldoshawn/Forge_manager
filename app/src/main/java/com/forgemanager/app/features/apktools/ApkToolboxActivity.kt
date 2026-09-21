package com.forgemanager.app.features.apktools

import android.app.AlertDialog
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.forgemanager.app.core.ui.ForgeActivity
import com.forgemanager.app.features.resources.ApkResourceStudioActivity
import com.forgemanager.app.features.settings.UiPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

class ApkToolboxActivity : ForgeActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var packageFile: File
    private lateinit var status: TextView
    private lateinit var output: TextView
    private var currentApk: File? = null
    private var extractedDirectory: File? = null
    private var alignedApk: File? = null
    private var signedApk: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_APK_PATH) ?: intent.getStringExtra("path")
        packageFile = path?.let(::File) ?: run { finish(); return }
        if (!packageFile.isFile) { finish(); return }
        if (packageFile.extension.equals("apk", true)) currentApk = packageFile
        setContentView(buildUi())
        append("Pacote: ${packageFile.path}\nTamanho: ${packageFile.length() / 1024} KB\n\n")
        currentApk?.let(::verify) ?: append("Pacote composto detectado. Use XAPK→APK ou EXTRAIR.\n\n")
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(UiPreferences.background(this@ApkToolboxActivity))
        val top = LinearLayout(this@ApkToolboxActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(UiPreferences.surface(this@ApkToolboxActivity))
        }
        top.addView(button("←") { finish() })
        status = TextView(this@ApkToolboxActivity).apply {
            text = packageFile.name
            setTextColor(UiPreferences.textPrimary(this@ApkToolboxActivity))
            maxLines = 2
            setPadding(dp(6), 0, dp(6), 0)
        }
        top.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        addView(top, LinearLayout.LayoutParams(-1, dp(54)))

        addActionRow(
            action("RESOURCE STUDIO") { openResourceStudio() },
            action("EXTRAIR / DESCOMPILAR") { extractPackage() }
        )
        addActionRow(
            action("RECOMPILAR APK") { rebuildApk() },
            action("XAPK → APK") { convertXapk() }
        )
        addActionRow(
            action("ZIPALIGN") { align() },
            action("ASSINAR v1/v2/v3/v4") { sign() }
        )
        addActionRow(
            action("VERIFICAR") { activeApk()?.let(::verify) ?: showError("Nenhum APK ativo") },
            action("LIMPAR SAÍDAS") { alignedApk = null; signedApk = null; append("Saídas temporárias desmarcadas.\n\n") }
        )

        output = TextView(this@ApkToolboxActivity).apply {
            setTextColor(UiPreferences.textPrimary(this@ApkToolboxActivity))
            setBackgroundColor(UiPreferences.background(this@ApkToolboxActivity))
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }
        addView(ScrollView(this@ApkToolboxActivity).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun LinearLayout.addActionRow(left: Button, right: Button) {
        addView(LinearLayout(this@ApkToolboxActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(UiPreferences.elevatedSurface(this@ApkToolboxActivity))
            addView(left)
            addView(right)
        }, LinearLayout.LayoutParams(-1, dp(50)))
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(UiPreferences.textPrimary(this@ApkToolboxActivity))
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setOnClickListener { action() }
    }

    private fun action(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 9.3f
        setTextColor(UiPreferences.textPrimary(this@ApkToolboxActivity))
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
    }

    private fun openResourceStudio() {
        val apk = activeApk() ?: run { showError("Converta ou recompile para APK primeiro"); return }
        startActivity(android.content.Intent(this, ApkResourceStudioActivity::class.java)
            .putExtra(ApkResourceStudioActivity.EXTRA_APK_PATH, apk.path))
    }

    private fun extractPackage() {
        val parent = packageFile.parentFile ?: filesDir
        val base = packageFile.name.substringBeforeLast('.', packageFile.name)
        val destination = File(parent, "$base-decompiled-${System.currentTimeMillis()}")
        status.text = "Extraindo ${packageFile.name}…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { ApkPackageTools.extractArchive(packageFile, destination) } }
                .onSuccess { result ->
                    extractedDirectory = result.directory
                    status.text = "${result.directory.name} • extraído"
                    append("EXTRAÇÃO concluída\n  pasta: ${result.directory.path}\n  entradas: ${result.files}\n  dados: ${result.bytes / 1024} KB\n\n")
                    toast("Pacote extraído")
                }.onFailure { showError(it.message ?: "Falha ao extrair pacote") }
        }
    }

    private fun rebuildApk() {
        val directory = extractedDirectory ?: run {
            showError("Extraia/descompile o pacote primeiro. A recompilação usa a pasta criada pelo Forge Manager.")
            return
        }
        val destination = sibling("-rebuilt-unsigned.apk")
        status.text = "Recompilando ${directory.name}…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { ApkPackageTools.repackDirectory(directory, destination) } }
                .onSuccess { result ->
                    currentApk = result.apk
                    alignedApk = null
                    signedApk = null
                    status.text = "${result.apk.name} • APK reconstruído"
                    append("RECOMPILAÇÃO concluída\n  entradas: ${result.entries}\n  saída: ${result.apk.path}\n  tamanho: ${result.bytes / 1024} KB\n  estado: não assinado; use ASSINAR para instalação.\n\n")
                    verify(result.apk)
                }.onFailure { showError(it.message ?: "Falha ao recompilar APK") }
        }
    }

    private fun convertXapk() {
        val ext = packageFile.extension.lowercase()
        if (ext !in setOf("xapk", "apkm", "apks", "zip")) {
            showError("Esta ação é destinada a XAPK/APKM/APKS. Para APK comum use EXTRAIR/RECOMPILAR.")
            return
        }
        val destination = sibling("-converted.apk")
        status.text = "Convertendo ${packageFile.name}…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { ApkPackageTools.xapkToApk(packageFile, destination) } }
                .onSuccess { result ->
                    currentApk = result.apk
                    alignedApk = null
                    signedApk = null
                    status.text = "${result.apk.name} • convertido"
                    append("XAPK → APK\n  APK escolhido: ${result.selectedEntry}\n  APKs no pacote: ${result.splitCount}\n  saída: ${result.apk.path}\n")
                    if (result.splitCount > 1) append("  aviso: pacote contém splits; o APK base pode depender dos demais splits.\n")
                    append("\n")
                    verify(result.apk)
                }.onFailure { showError(it.message ?: "Falha ao converter XAPK") }
        }
    }

    private fun align() {
        val source = activeApk() ?: run { showError("Nenhum APK ativo"); return }
        val destination = sibling("-aligned.apk")
        status.text = "Alinhando…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { ZipAligner.align(source, destination, 4) } }
                .onSuccess { r ->
                    alignedApk = destination
                    signedApk = null
                    status.text = "${destination.name} • pronto"
                    append("ZIPALIGN concluído\n  entradas: ${r.entries}\n  STORED alinhadas: ${r.alignedStoredEntries}\n  saída: ${destination.path}\n\n")
                    toast("APK alinhado")
                }.onFailure { showError(it.message ?: "Falha no zipalign") }
        }
    }

    private fun sign() {
        val source = alignedApk?.takeIf { it.isFile } ?: activeApk() ?: run { showError("Nenhum APK ativo"); return }
        val destination = sibling("-signed.apk")
        val idsig = File(destination.path + ".idsig")
        status.text = "Assinando v1/v2/v3/v4…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val signer = getOrCreateSigner()
                destination.delete(); idsig.delete()
                val config = ApkSigner.SignerConfig.Builder("FORGE", signer.first, listOf(signer.second)).build()
                ApkSigner.Builder(listOf(config))
                    .setInputApk(source)
                    .setOutputApk(destination)
                    .setOtherSignersSignaturesPreserved(false)
                    .setMinSdkVersion(21)
                    .setV1SigningEnabled(true)
                    .setV2SigningEnabled(true)
                    .setV3SigningEnabled(true)
                    .setV4SigningEnabled(true)
                    .setV4SignatureOutputFile(idsig)
                    .build().sign()
                require(destination.isFile && destination.length() > 0)
                destination.length() to idsig.exists()
            }}.onSuccess { (size, v4) ->
                signedApk = destination
                currentApk = destination
                status.text = "${destination.name} • assinado"
                append("ASSINATURA concluída\n  v1/v2/v3: habilitadas\n  v4 .idsig: ${if (v4) "criada" else "não criada"}\n  saída: ${destination.path}\n  tamanho: ${size / 1024} KB\n\n")
                verify(destination)
            }.onFailure { showError(it.message ?: "Falha ao assinar APK") }
        }
    }

    private fun activeApk(): File? = signedApk?.takeIf { it.isFile }
        ?: alignedApk?.takeIf { it.isFile }
        ?: currentApk?.takeIf { it.isFile }

    private fun verify(file: File) {
        status.text = "Verificando ${file.name}…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val r = ApkVerifier.Builder(file).build().verify()
                Verification(
                    r.isVerified,
                    flag(r, "isVerifiedUsingV1Scheme"), flag(r, "isVerifiedUsingV2Scheme"),
                    flag(r, "isVerifiedUsingV3Scheme"), flag(r, "isVerifiedUsingV4Scheme"),
                    r.errors.take(8).joinToString("\n"), r.warnings.take(8).joinToString("\n")
                )
            }}.onSuccess { v ->
                status.text = "${file.name} • ${if (v.verified) "assinatura OK" else "não verificado"}"
                append("VERIFICAÇÃO: ${file.name}\n  geral: ${v.verified}\n  v1:${v.v1} v2:${v.v2} v3:${v.v3} v4:${v.v4}\n${if (v.errors.isNotBlank()) "erros:\n${v.errors}\n" else ""}${if (v.warnings.isNotBlank()) "avisos:\n${v.warnings}\n" else ""}\n")
            }.onFailure { append("Verificação falhou: ${it.message}\n\n") }
        }
    }

    private fun getOrCreateSigner(): Pair<PrivateKey, X509Certificate> {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(KEY_ALIAS)) {
            val now = System.currentTimeMillis()
            val g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=Forge Manager Local Signer,O=Forge Manager"))
                .setCertificateSerialNumber(BigInteger.valueOf(now.coerceAtLeast(1)))
                .setCertificateNotBefore(Date(now - DAY_MS))
                .setCertificateNotAfter(Date(now + 20L * 365 * DAY_MS))
                .build()
            g.initialize(spec); g.generateKeyPair()
        }
        return (ks.getKey(KEY_ALIAS, null) as PrivateKey) to (ks.getCertificate(KEY_ALIAS) as X509Certificate)
    }

    private fun flag(result: Any, method: String) = runCatching {
        result.javaClass.getMethod(method).invoke(result) as? Boolean ?: false
    }.getOrDefault(false)

    private fun sibling(suffix: String): File {
        val base = packageFile.name.substringBeforeLast('.', packageFile.name)
        return File(packageFile.parentFile ?: filesDir, base + suffix)
    }

    private fun append(v: String) { output.append(v) }
    private fun showError(m: String) = runCatching {
        if (!isFinishing && !isDestroyed) AlertDialog.Builder(this).setTitle("APK Toolbox").setMessage(m).setPositiveButton("OK", null).show()
    }
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private data class Verification(val verified: Boolean, val v1: Boolean, val v2: Boolean, val v3: Boolean, val v4: Boolean, val errors: String, val warnings: String)

    companion object {
        const val EXTRA_APK_PATH = "apk_path"
        private const val KEY_ALIAS = "forge_manager_local_apk_signer_v1"
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
