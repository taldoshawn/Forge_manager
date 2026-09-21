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
        // Probe every ignorable-character alias. If any form is readable/writable,
        // stay on DirectFileBackend — no error dialog, no Shizuku/root required.
        for (candidate in PathSecurity.bypassCandidates(location.path)) {
            val file = File(candidate)
            val ok = if (write) {
                (file.exists() && file.canWrite()) ||
                    (!file.exists() && file.parentFile?.canWrite() == true)
            } else {
                file.canRead()
            }
            if (ok) return direct
        }
        if (shizuku.supports(location)) return shizuku
        if (root.supports(location)) return root
        throw FileAccessException("O Android bloqueou este caminho. Autorize uma pasta SAF, Shizuku ou root.")
    }

    fun directBackend() = direct
    fun rootBackend() = root
}
