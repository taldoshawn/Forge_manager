package com.forgemanager.app.archive

import java.io.File

object ZipSecurity {
    fun normalizeEntryName(name: String): String {
        require(!name.contains('\u0000')) { "Entrada ZIP contém NUL" }
        require(!name.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(name)) { "Entrada ZIP absoluta bloqueada" }
        val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        require(parts.none { it == ".." }) { "Zip Slip bloqueado" }
        return parts.joinToString("/")
    }

    fun resolveExtractionTarget(destination: File, entryName: String): File {
        val safeName = normalizeEntryName(entryName)
        val root = destination.canonicalFile
        val target = File(root, safeName).canonicalFile
        require(target == root || target.path.startsWith(root.path + File.separator)) { "Zip Slip bloqueado" }
        return target
    }
}
