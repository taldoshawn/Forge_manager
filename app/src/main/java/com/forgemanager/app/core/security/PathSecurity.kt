package com.forgemanager.app.core.security

import android.os.Environment
import java.io.File
import java.nio.file.Path

object PathSecurity {
    // Characters ignored by casefold / path normalization on affected kernels.
    // Security policy matches exact "Android/data" / "Android/obb" and misses these aliases.
    private const val ZWSP = "\u200B"   // ZERO WIDTH SPACE
    private const val ZWNJ = "\u200C"   // ZERO WIDTH NON-JOINER
    private const val ZWJ = "\u200D"    // ZERO WIDTH JOINER
    private const val BOM = "\uFEFF"    // ZERO WIDTH NO-BREAK SPACE

    private val IGNORABLES = listOf(ZWSP, ZWNJ, ZWJ, BOM)

    fun validateName(name: String) {
        require(name.isNotBlank()) { "O nome não pode estar vazio" }
        require(name != "." && name != "..") { "Nome reservado" }
        require(!name.contains('/') && !name.contains('\u0000')) { "Nome inválido" }
        require(name.length <= 255) { "Nome muito longo" }
    }

    fun resolveChild(parent: File, name: String): File {
        validateName(name)
        // Do not canonicalize — that strips the ZWSP alias on restricted trees.
        val child = File(parent, name)
        val parentPath = parent.path.trimEnd('/')
        val childPath = child.path.trimEnd('/')
        require(childPath == parentPath || childPath.startsWith("$parentPath/")) {
            "Caminho fora da pasta de destino"
        }
        return child
    }

    fun isWithin(root: Path, candidate: Path): Boolean {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedCandidate = candidate.toAbsolutePath().normalize()
        return normalizedCandidate.startsWith(normalizedRoot)
    }

    fun restrictedExternalPath(segment: String): String {
        val base = Environment.getExternalStorageDirectory()
        return File(base, "Android/$ZWSP$segment").path
    }

    fun androidDataBypass(): String = restrictedExternalPath("data")
    fun androidObbBypass(): String = restrictedExternalPath("obb")

    /** Strip every known ignorable so we can detect the plain restricted path. */
    fun stripIgnorables(path: String): String {
        var p = path
        for (ch in IGNORABLES) p = p.replace(ch, "")
        return p
    }

    fun isRestrictedExternal(path: String): Boolean {
        val normalized = stripIgnorables(path).trimEnd('/')
        return normalized.contains("/Android/data") ||
            normalized.endsWith("/Android/data") ||
            normalized.contains("/Android/obb") ||
            normalized.endsWith("/Android/obb")
    }

    /**
     * All candidate path strings for a restricted location.
     * Policy string-matches the plain form; these aliases miss that check while the
     * filesystem still resolves to the real directory.
     */
    fun bypassCandidates(path: String): List<String> {
        if (path.isBlank()) return listOf(path)
        val plain = stripIgnorables(path).trimEnd('/')
        if (!isRestrictedExternal(plain)) return listOf(path)

        val out = LinkedHashSet<String>()
        out += path
        out += plain

        // Inject each ignorable in the common positions MT Manager / known exploits use.
        for (ch in IGNORABLES) {
            // Android/<ch>data  and  Android/data<ch>
            out += plain.replace("/Android/data", "/Android/${ch}data")
            out += plain.replace("/Android/data", "/Android/data$ch")
            out += plain.replace("/Android/obb", "/Android/${ch}obb")
            out += plain.replace("/Android/obb", "/Android/obb$ch")
            // also mid-name forms seen in some reports
            out += plain.replace("/Android/data", "/Android/d${ch}ata")
            out += plain.replace("/Android/obb", "/Android/o${ch}bb")
        }
        return out.toList()
    }

    /**
     * Prefer a candidate the process can actually read (or write).
     * Falls back to the primary ZWSP form so callers always get a rewritten path
     * even when canRead is false (AccessResolver will then try Shizuku/root).
     */
    fun openablePath(path: String, write: Boolean = false): String {
        val candidates = bypassCandidates(path)
        for (candidate in candidates) {
            val f = File(candidate)
            val ok = if (write) {
                (f.exists() && f.canWrite()) || (!f.exists() && f.parentFile?.canWrite() == true)
            } else {
                f.canRead()
            }
            if (ok) return candidate
        }
        // Primary known-working form even if this process can't probe it yet.
        return maybeBypassRestricted(path)
    }

    /**
     * Deterministic rewrite to the primary ZWSP form (Android/\u200Bdata|obb).
     * Used when we only need a stable path string, not a live canRead probe.
     */
    fun maybeBypassRestricted(path: String): String {
        if (path.isBlank()) return path
        val plain = stripIgnorables(path)
        if (!isRestrictedExternal(plain)) return path

        var result = plain
        if (result.contains("/Android/data") || result.endsWith("/Android/data")) {
            result = result.replace("/Android/data", "/Android/${ZWSP}data")
        }
        if (result.contains("/Android/obb") || result.endsWith("/Android/obb")) {
            result = result.replace("/Android/obb", "/Android/${ZWSP}obb")
        }
        return result
    }
}
