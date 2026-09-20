package com.forgemanager.app.features.apk

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

class ApkSignerActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var inputApk: File
    private lateinit var keyStorePath: EditText
    private lateinit var alias: EditText
    private lateinit var storePassword: EditText
    private lateinit var keyPassword: EditText
    private lateinit var outputPath: EditText
    private lateinit var status: TextView
    private lateinit var v1: CheckBox
    private lateinit var v2: CheckBox
    private lateinit var v3: CheckBox
    private lateinit var v4: CheckBox
    private lateinit var align: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inputApk = File(intent.getStringExtra(EXTRA_APK_PATH) ?: run { finish(); return })
        setContentView(buildUi())
    }

    override fun onDestroy() {
        storePassword.text?.clear()
        keyPassword.text?.clear()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)
        val bar = LinearLayout(this@ApkSignerActivity).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.BLACK) }
        bar.addView(button("←") { finish() })
        bar.addView(TextView(this@ApkSignerActivity).apply {
            text = "Assinar APK"
            setTextColor(Color.WHITE)
            textSize = 16f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(bar, LinearLayout.LayoutParams(-1, dp(54)))

        val body = LinearLayout(this@ApkSignerActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(20))
        }
        fun field(hint: String, value: String = "", password: Boolean = false) = EditText(this@ApkSignerActivity).apply {
            this.hint = hint
            setText(value)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        keyStorePath = field("Caminho do keystore (.jks/.p12/.pfx)")
        alias = field("Alias (vazio = primeiro alias com chave)")
        storePassword = field("Senha do keystore", password = true)
        keyPassword = field("Senha da chave (vazio = mesma do keystore)", password = true)
        outputPath = field("APK de saída", File(inputApk.parentFile, "${inputApk.nameWithoutExtension}-signed.apk").path)
        body.addView(keyStorePath)
        body.addView(alias)
        body.addView(storePassword)
        body.addView(keyPassword)
        body.addView(outputPath)

        align = checkbox("Zipalign 4-byte antes de assinar", true)
        v1 = checkbox("APK Signature Scheme v1 (JAR)", true)
        v2 = checkbox("APK Signature Scheme v2", true)
        v3 = checkbox("APK Signature Scheme v3", true)
        v4 = checkbox("APK Signature Scheme v4 (.idsig)", true)
        body.addView(align); body.addView(v1); body.addView(v2); body.addView(v3); body.addView(v4)
        body.addView(Button(this@ApkSignerActivity).apply {
            text = "ZIPALIGN + ASSINAR"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(0, 95, 145))
            setOnClickListener { confirmSign() }
        })
        status = TextView(this@ApkSignerActivity).apply {
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(12), 0, 0)
            text = "As senhas ficam somente em memória e não são gravadas pelo Forge Manager."
        }
        body.addView(status)
        addView(ScrollView(this@ApkSignerActivity).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun checkbox(label: String, checked: Boolean) = CheckBox(this).apply {
        text = label
        isChecked = checked
        setTextColor(Color.WHITE)
        buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(0, 190, 255))
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { action() }
    }

    private fun confirmSign() {
        if (v4.isChecked && !v2.isChecked && !v3.isChecked) {
            showError("A assinatura v4 exige v2 ou v3.")
            return
        }
        AlertDialog.Builder(this).setTitle("Assinar APK")
            .setMessage("A saída será assinada com a chave privada selecionada. O Forge Manager não salva as senhas. Não use uma chave de produção em APKs de origem não confiável sem revisar o conteúdo.")
            .setPositiveButton("Assinar") { _, _ -> sign() }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun sign() {
        val keyFile = File(keyStorePath.text.toString())
        val output = File(outputPath.text.toString())
        val storePass = storePassword.text.toString().toCharArray()
        val keyPass = keyPassword.text.toString().ifBlank { storePassword.text.toString() }.toCharArray()
        val requestedAlias = alias.text.toString().trim()
        val useAlign = align.isChecked
        val useV1 = v1.isChecked; val useV2 = v2.isChecked; val useV3 = v3.isChecked; val useV4 = v4.isChecked
        status.text = "Preparando assinatura…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    require(inputApk.isFile) { "APK de entrada não encontrado" }
                    require(keyFile.isFile) { "Keystore não encontrado" }
                    require(output.canonicalFile != inputApk.canonicalFile) { "Escolha outro caminho de saída" }
                    output.parentFile?.mkdirs()
                    val store = loadKeyStore(keyFile, storePass)
                    val selectedAlias = requestedAlias.ifBlank {
                        val names = store.aliases()
                        generateSequence { if (names.hasMoreElements()) names.nextElement() else null }
                            .firstOrNull { store.isKeyEntry(it) }
                            ?: error("Nenhum alias com chave privada encontrado")
                    }
                    val key = store.getKey(selectedAlias, keyPass) as? PrivateKey ?: error("Alias não contém chave privada")
                    val certificates = store.getCertificateChain(selectedAlias)?.mapNotNull { it as? X509Certificate }
                        ?: listOfNotNull(store.getCertificate(selectedAlias) as? X509Certificate)
                    require(certificates.isNotEmpty()) { "Certificado X.509 não encontrado" }

                    val signerConfig = ApkSigner.SignerConfig.Builder(selectedAlias, key, certificates).build()
                    val alignedTemp = if (useAlign) File(cacheDir, "aligned-${System.nanoTime()}.apk") else null
                    val signInput = alignedTemp ?: inputApk
                    if (alignedTemp != null) ZipAligner.align(inputApk, alignedTemp, 4)
                    try {
                        val builder = ApkSigner.Builder(listOf(signerConfig))
                            .setInputApk(signInput)
                            .setOutputApk(output)
                            .setOtherSignersSignaturesPreserved(false)
                            .setV1SigningEnabled(useV1)
                            .setV2SigningEnabled(useV2)
                            .setV3SigningEnabled(useV3)
                            .setV4SigningEnabled(useV4)
                        val idsig = File(output.path + ".idsig")
                        if (useV4) builder.setV4SignatureOutputFile(idsig) else idsig.delete()
                        builder.build().sign()
                        val verification = ApkVerifier.Builder(output).build().verify()
                        require(verification.isVerified) {
                            "APK criado, mas a verificação falhou: " + verification.errors.joinToString { it.toString() }
                        }
                        SignResult(output, if (useV4) idsig else null, selectedAlias, verification.warnings.size)
                    } finally {
                        alignedTemp?.delete()
                    }
                }
            }.onSuccess { result ->
                status.text = buildString {
                    append("Assinado e verificado\n")
                    append(result.output.path).append("\nAlias: ").append(result.alias)
                    result.idsig?.let { append("\nV4: ").append(it.path) }
                    if (result.warningCount > 0) append("\nAvisos do verificador: ").append(result.warningCount)
                }
                Toast.makeText(this@ApkSignerActivity, "APK assinado com sucesso", Toast.LENGTH_LONG).show()
            }.onFailure { showError(it.message ?: "Falha ao assinar APK") }
            storePass.fill('\u0000'); keyPass.fill('\u0000')
        }
    }

    private fun loadKeyStore(file: File, password: CharArray): KeyStore {
        val preferred = when (file.extension.lowercase()) {
            "p12", "pfx" -> listOf("PKCS12", KeyStore.getDefaultType())
            else -> listOf("JKS", "PKCS12", KeyStore.getDefaultType())
        }.distinct()
        var last: Throwable? = null
        for (type in preferred) {
            try {
                return KeyStore.getInstance(type).also { store -> FileInputStream(file).use { store.load(it, password) } }
            } catch (error: Throwable) { last = error }
        }
        throw IllegalArgumentException("Não foi possível abrir o keystore", last)
    }

    private data class SignResult(val output: File, val idsig: File?, val alias: String, val warningCount: Int)

    private fun showError(message: String) {
        status.text = "Falha: $message"
        AlertDialog.Builder(this).setTitle("Assinatura APK").setMessage(message).setPositiveButton("OK", null).show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object { const val EXTRA_APK_PATH = "apk_path" }
}
