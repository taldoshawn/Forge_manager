package com.forgemanager.app.core.file

import com.forgemanager.app.archive.ArchiveFileBackend
import com.forgemanager.app.core.security.PathSecurity
import java.io.File

class AccessResolver(
    private val direct: DirectFileBackend,
    private val saf: SafFileBackend,
    private val shizuku: ShizukuFileBackend,
    private val root: RootFileBackend,
    private val archive: ArchiveFileBackend
) {
    suspend fun backendFor(location: FileLocation, write: Boolean = false): FileBackend = when (location) {
        is FileLocation.Saf -> saf
        is FileLocation.Archive -> archive
        is FileLocation.Direct -> resolveDirect(location, write)
    }

    private suspend fun resolveDirect(location: FileLocation.Direct, write: Boolean): FileBackend {
        // Prefer the ZWSP-rewritten path for restricted external dirs so direct access wins
        // without falling through to Shizuku/root or throwing.
        val candidatePath = PathSecurity.maybeBypassRestricted(location.path)
        val file = File(candidatePath)
        val directAllowed = if (write) {
            (file.exists() && file.canWrite()) || (!file.exists() && file.parentFile?.canWrite() == true)
        } else file.canRead()
        if (directAllowed) return direct
        if (shizuku.supports(location)) return shizuku
        if (root.supports(location)) return root
        throw FileAccessException("O Android bloqueou este caminho. Autorize uma pasta SAF, Shizuku ou root.")
    }

    fun directBackend() = direct
    fun rootBackend() = root
}
