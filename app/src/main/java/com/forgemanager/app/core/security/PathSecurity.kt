package com.forgemanager.app.core.security

import android.os.Environment
import java.io.File
import java.nio.file.Path

object PathSecurity {
    // Zero-width space. Ignored by casefold / path normalization on affected kernels.
    // Security policy matches exact "Android/data" / "Android/obb" strings and misses this alias.
    private const val ZWSP = "\u200B"

    fun validateName(name: String) {
        require(name.isNotBlank()) { "O nome não pode estar vazio" }
        require(name != "." && name != "..") { "Nome reservado" }
        require(!name.contains('/') && !name.contains('\u0000')) { "Nome inválido" }
        require(name.length <= 255) { "Nome muito longo" }
    }

    fun resolveChild(parent: File, name: String): File {
        validateName(name)
        val canonicalParent = parent.canonicalFile
        val child = File(canonicalParent, name).canonicalFile
        require(child.parentFile == canonicalParent) { "Caminho fora da pasta de destino" }
        return child
    }

    fun isWithin(root: Path, candidate: Path): Boolean {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedCandidate = candidate.toAbsolutePath().normalize()
        return normalizedCandidate.startsWith(normalizedRoot)
    }

    /**
     * Builds a path that the storage policy does not recognize as restricted
     * while the filesystem still resolves it to the real Android/data or Android/obb.
     *
     * Flow:
     *   1. App supplies a path containing ZWSP
     *   2. Policy string-matches against "Android/data" / "Android/obb" → miss
     *   3. Kernel casefold / unicode ignore collapses the character
     *   4. Resolution lands on the real directory → unlocked
     */
    fun restrictedExternalPath(segment: String): String {
        val base = Environment.getExternalStorageDirectory()
        return File(base, "Android/$ZWSP$segment").path
    }

    fun androidDataBypass(): String = restrictedExternalPath("data")
    fun androidObbBypass(): String = restrictedExternalPath("obb")

    /**
     * If [path] is Android/data, Android/obb, or anything under them (plain form),
     * rewrite the restricted segment to the ZWSP alias so the kernel opens the real dir
     * while the policy string-match fails.
     * Already-bypassed paths and unrelated paths are returned unchanged.
     */
    fun maybeBypassRestricted(path: String): String {
        if (path.isBlank()) return path
        val base = Environment.getExternalStorageDirectory().path.trimEnd('/')
        val dataPlain = "$base/Android/data"
        val obbPlain = "$base/Android/obb"
        val dataBypass = androidDataBypass()
        val obbBypass = androidObbBypass()
        val p = path.trimEnd('/')

        return when {
            p == dataPlain || p.startsWith("$dataPlain/") ->
                dataBypass + p.removePrefix(dataPlain)
            p == obbPlain || p.startsWith("$obbPlain/") ->
                obbBypass + p.removePrefix(obbPlain)
            // also catch /sdcard style if it appears
            p.endsWith("/Android/data") || p.contains("/Android/data/") ->
                p.replace("/Android/data", "/Android/$ZWSP" + "data")
            p.endsWith("/Android/obb") || p.contains("/Android/obb/") ->
                p.replace("/Android/obb", "/Android/$ZWSP" + "obb")
            else -> path
        }
    }

    fun isRestrictedExternal(path: String): Boolean {
        val normalized = path.replace(ZWSP, "").trimEnd('/')
        val base = Environment.getExternalStorageDirectory().path.trimEnd('/')
        return normalized == "$base/Android/data" ||
            normalized.startsWith("$base/Android/data/") ||
            normalized == "$base/Android/obb" ||
            normalized.startsWith("$base/Android/obb/") ||
            normalized.endsWith("/Android/data") ||
            normalized.contains("/Android/data/") ||
            normalized.endsWith("/Android/obb") ||
            normalized.contains("/Android/obb/")
    }
}
