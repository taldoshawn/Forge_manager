package com.forgemanager.app.features.apktools

import com.forgemanager.app.core.ui.ForgeActivity

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
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
import com.forgemanager.app.features.resources.ApkResourceStudioActivity
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
    private lateinit var apk: File
    private lateinit var status: TextView
    private lateinit var output: TextView
    private var alignedApk: File? = null
    private var signedApk: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_APK_PATH) ?: intent.getStringExtra("path")
        apk = path?.let(::File) ?: run { finish(); return }
        if (!apk.isFile) { finish(); return }
        setContentView(buildUi())
        append("APK: ${apk.path}\nTamanho: ${apk.length() / 1024} KB\n\n")
        verify(apk)
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK)
        val top = LinearLayout(this@ApkToolboxActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.rgb(8,8,8)) }
        top.addView(button("←") { finish() })
        status = TextView(this@ApkToolboxActivity).apply { text = apk.name; setTextColor(Color.WHITE); maxLines = 2; setPadding(dp(6),0,dp(6),0) }
        top.addView(status, LinearLayout.LayoutParams(0,-2,1f)); addView(top, LinearLayout.LayoutParams(-1,dp(54)))
        val row1 = LinearLayout(this@ApkToolboxActivity).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.rgb(3,3,3)) }
        row1.addView(action("Resource Studio") { startActivity(android.content.Intent(this@ApkToolboxActivity, ApkResourceStudioActivity::class.java).putExtra(ApkResourceStudioActivity.EXTRA_APK_PATH, apk.path)) })
        row1.addView(action("ZIPALIGN") { align() }); addView(row1, LinearLayout.LayoutParams(-1,dp(50)))
        val row2 = LinearLayout(this@ApkToolboxActivity).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.rgb(3,3,3)) }
        row2.addView(action("ASSINAR v1/v2/v3/v4") { sign() }); row2.addView(action("VERIFICAR") { verify(signedApk ?: alignedApk ?: apk) }); addView(row2, LinearLayout.LayoutParams(-1,dp(50)))
        output = TextView(this@ApkToolboxActivity).apply { setTextColor(Color.rgb(215,222,232)); typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f; setTextIsSelectable(true); setPadding(dp(12),dp(12),dp(12),dp(24)) }
        addView(ScrollView(this@ApkToolboxActivity).apply { addView(output) }, LinearLayout.LayoutParams(-1,0,1f))
    }

    private fun button(label:String, action:()->Unit)=Button(this).apply{text=label;setTextColor(Color.WHITE);setBackgroundColor(Color.TRANSPARENT);setOnClickListener{action()}}
    private fun action(label:String, action:()->Unit)=Button(this).apply{text=label;textSize=11f;setTextColor(Color.WHITE);setBackgroundColor(Color.TRANSPARENT);setOnClickListener{action()};layoutParams=LinearLayout.LayoutParams(0,-1,1f)}

    private fun align() {
        val destination=sibling("-aligned.apk"); status.text="Alinhando…"
        scope.launch { runCatching { withContext(Dispatchers.IO){ ZipAligner.align(apk,destination,4) } }.onSuccess { r -> alignedApk=destination;status.text="${destination.name} • pronto";append("ZIPALIGN concluído\n  entradas: ${r.entries}\n  STORED alinhadas: ${r.alignedStoredEntries}\n  saída: ${destination.path}\n\n");toast("APK alinhado") }.onFailure { showError(it.message?:"Falha no zipalign") } }
    }

    private fun sign() {
        val source=alignedApk?.takeIf{it.isFile}?:apk; val destination=sibling("-signed.apk"); val idsig=File(destination.path+".idsig"); status.text="Assinando v1/v2/v3/v4…"
        scope.launch { runCatching { withContext(Dispatchers.IO) {
            val signer=getOrCreateSigner(); destination.delete(); idsig.delete()
            val config=ApkSigner.SignerConfig.Builder("FORGE", signer.first, listOf(signer.second)).build()
            ApkSigner.Builder(listOf(config)).setInputApk(source).setOutputApk(destination).setOtherSignersSignaturesPreserved(false).setMinSdkVersion(21).setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(true).setV4SigningEnabled(true).setV4SignatureOutputFile(idsig).build().sign()
            require(destination.isFile&&destination.length()>0); destination.length() to idsig.exists()
        }}.onSuccess { (size,v4) -> signedApk=destination;status.text="${destination.name} • assinado";append("ASSINATURA concluída\n  v1/v2/v3: habilitadas\n  v4 .idsig: ${if(v4)"criada" else "não criada"}\n  saída: ${destination.path}\n  tamanho: ${size/1024} KB\n\n");verify(destination) }.onFailure { showError(it.message?:"Falha ao assinar APK") } }
    }

    private fun verify(file:File) {
        status.text="Verificando ${file.name}…"
        scope.launch { runCatching { withContext(Dispatchers.IO) { val r=ApkVerifier.Builder(file).build().verify(); Verification(r.isVerified,flag(r,"isVerifiedUsingV1Scheme"),flag(r,"isVerifiedUsingV2Scheme"),flag(r,"isVerifiedUsingV3Scheme"),flag(r,"isVerifiedUsingV4Scheme"),r.errors.take(8).joinToString("\n"),r.warnings.take(8).joinToString("\n")) } }.onSuccess { v -> status.text="${file.name} • ${if(v.verified)"assinatura OK" else "não verificado"}";append("VERIFICAÇÃO: ${file.name}\n  geral: ${v.verified}\n  v1:${v.v1} v2:${v.v2} v3:${v.v3} v4:${v.v4}\n${if(v.errors.isNotBlank())"erros:\n${v.errors}\n" else ""}${if(v.warnings.isNotBlank())"avisos:\n${v.warnings}\n" else ""}\n") }.onFailure { append("Verificação falhou: ${it.message}\n\n") } }
    }

    private fun getOrCreateSigner():Pair<PrivateKey,X509Certificate>{
        val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        if(!ks.containsAlias(KEY_ALIAS)){ val now=System.currentTimeMillis(); val g=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore"); val spec=KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY).setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA512).setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1).setCertificateSubject(X500Principal("CN=Forge Manager Local Signer,O=Forge Manager")).setCertificateSerialNumber(BigInteger.valueOf(now.coerceAtLeast(1))).setCertificateNotBefore(Date(now-DAY_MS)).setCertificateNotAfter(Date(now+20L*365*DAY_MS)).build();g.initialize(spec);g.generateKeyPair() }
        return (ks.getKey(KEY_ALIAS,null) as PrivateKey) to (ks.getCertificate(KEY_ALIAS) as X509Certificate)
    }
    private fun flag(result:Any,method:String)=runCatching{result.javaClass.getMethod(method).invoke(result) as? Boolean?:false}.getOrDefault(false)
    private fun sibling(suffix:String)=File(apk.parentFile?:filesDir,apk.name.removeSuffix(".apk")+suffix)
    private fun append(v:String){output.append(v)}
    private fun showError(m:String)=AlertDialog.Builder(this).setTitle("APK Toolbox").setMessage(m).setPositiveButton("OK",null).show()
    private fun toast(m:String)=Toast.makeText(this,m,Toast.LENGTH_SHORT).show()
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private data class Verification(val verified:Boolean,val v1:Boolean,val v2:Boolean,val v3:Boolean,val v4:Boolean,val errors:String,val warnings:String)
    companion object{const val EXTRA_APK_PATH="apk_path";private const val KEY_ALIAS="forge_manager_local_apk_signer_v1";private const val DAY_MS=24L*60*60*1000}
}
