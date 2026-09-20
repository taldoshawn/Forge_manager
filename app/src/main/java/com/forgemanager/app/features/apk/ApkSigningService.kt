package com.forgemanager.app.features.apk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.security.auth.x500.X500Principal

/** Uses AndroidKeyStore so the private APK signing key never leaves the device. */
object ApkSigningService {
    private const val STORE = "AndroidKeyStore"
    private const val ALIAS = "forge_manager_local_apk_signer_v1"

    data class SigningResult(
        val apk: File,
        val idsig: File,
        val verified: Boolean,
        val v1: Boolean,
        val v2: Boolean,
        val v3: Boolean,
        val v4: Boolean,
        val certificateSha256: String
    )

    fun sign(context: Context, input: File, output: File): SigningResult {
        require(input.isFile && input.extension.equals("apk", true)) { "APK de entrada inválido" }
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        val idsig = File(output.path + ".idsig")
        if (idsig.exists()) idsig.delete()

        val entry = getOrCreateKey()
        val privateKey = entry.privateKey as PrivateKey
        val cert = entry.certificate as X509Certificate
        val config = ApkSigner.SignerConfig.Builder("FORGE", privateKey, listOf(cert)).build()

        ApkSigner.Builder(listOf(config))
            .setInputApk(input)
            .setOutputApk(output)
            .setOtherSignersSignaturesPreserved(false)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setV4SigningEnabled(true)
            .setV4SignatureOutputFile(idsig)
            .setAlignmentPreserved(true)
            .build()
            .sign()

        val verification = ApkVerifier.Builder(output)
            .setV4SignatureFile(idsig)
            .build()
            .verify()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02x".format(it) }
        return SigningResult(
            apk = output,
            idsig = idsig,
            verified = verification.isVerified,
            v1 = verification.isVerifiedUsingV1Scheme,
            v2 = verification.isVerifiedUsingV2Scheme,
            v3 = verification.isVerifiedUsingV3Scheme,
            v4 = verification.isVerifiedUsingV4Scheme,
            certificateSha256 = digest
        )
    }

    fun verify(apk: File): String {
        require(apk.isFile) { "APK não encontrado" }
        val idsig = File(apk.path + ".idsig")
        val builder = ApkVerifier.Builder(apk)
        if (idsig.isFile) builder.setV4SignatureFile(idsig)
        val result = builder.build().verify()
        return buildString {
            appendLine("Verificação: ${if (result.isVerified) "OK" else "FALHOU"}")
            appendLine("v1: ${result.isVerifiedUsingV1Scheme}")
            appendLine("v2: ${result.isVerifiedUsingV2Scheme}")
            appendLine("v3: ${result.isVerifiedUsingV3Scheme}")
            appendLine("v4: ${result.isVerifiedUsingV4Scheme}")
            appendLine("Signatários: ${result.signerCertificates.size}")
            if (result.errors.isNotEmpty()) {
                appendLine()
                appendLine("Erros:")
                result.errors.take(20).forEach { appendLine("• $it") }
            }
            if (result.warnings.isNotEmpty()) {
                appendLine()
                appendLine("Avisos:")
                result.warnings.take(20).forEach { appendLine("• $it") }
            }
        }
    }

    private fun getOrCreateKey(): KeyStore.PrivateKeyEntry {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let { return it }

        val now = Calendar.getInstance()
        val until = Calendar.getInstance().apply { add(Calendar.YEAR, 25) }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, STORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setKeySize(3072)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=Forge Manager Local APK Signer,O=Forge Manager"))
                .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis().coerceAtLeast(1)))
                .setCertificateNotBefore(now.time)
                .setCertificateNotAfter(until.time)
                .build()
        )
        generator.generateKeyPair()
        store.load(null)
        return store.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
    }
}
