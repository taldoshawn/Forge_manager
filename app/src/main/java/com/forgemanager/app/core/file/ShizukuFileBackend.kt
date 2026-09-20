package com.forgemanager.app.core.file

import android.os.ParcelFileDescriptor
import com.forgemanager.app.core.security.PathSecurity
import com.forgemanager.app.shizuku.ShizukuBridge
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

class ShizukuFileBackend(private val bridge: ShizukuBridge) : FileBackend {
    override val id: String = "shizuku"
    private val decoder = Base64.getUrlDecoder()

    override fun supports(location: FileLocation): Boolean = location is FileLocation.Direct && bridge.service != null
    private fun path(location: FileLocation) = (location as? FileLocation.Direct)?.path
        ?: throw FileAccessException("Caminho inválido")
    private fun api() = bridge.service ?: throw FileAccessException("Shizuku não está conectado")

    override suspend fun capabilities(location: FileLocation) =
        BackendCapabilities(read = true, write = true, randomAccess = true, permissions = false)

    override suspend fun list(location: FileLocation, showHidden: Boolean): List<FileNode> =
        api().list(path(location), showHidden).map(::decodeStat)

    override suspend fun stat(location: FileLocation): FileNode = decodeStat(api().stat(path(location)))

    override suspend fun openInput(location: FileLocation): InputStream =
        ParcelFileDescriptor.AutoCloseInputStream(api().openRead(path(location)))

    override suspend fun openOutput(location: FileLocation, truncate: Boolean): OutputStream =
        ParcelFileDescriptor.AutoCloseOutputStream(api().openWrite(path(location), truncate))

    override suspend fun create(parent: FileLocation, name: String): FileNode {
        PathSecurity.validateName(name)
        if (!api().create(path(parent), name)) throw FileAccessException("Falha ao criar arquivo via Shizuku")
        return stat(FileLocation.Direct(File(path(parent), name).path))
    }

    override suspend fun mkdir(parent: FileLocation, name: String): FileNode {
        PathSecurity.validateName(name)
        if (!api().mkdir(path(parent), name)) throw FileAccessException("Falha ao criar pasta via Shizuku")
        return stat(FileLocation.Direct(File(path(parent), name).path))
    }

    override suspend fun rename(source: FileLocation, newName: String): FileNode {
        PathSecurity.validateName(newName)
        val src = File(path(source))
        val target = File(src.parentFile, newName)
        if (!api().rename(src.path, target.path)) throw FileAccessException("Falha ao renomear via Shizuku")
        return stat(FileLocation.Direct(target.path))
    }

    override suspend fun delete(location: FileLocation) {
        if (!api().deleteRecursively(path(location))) throw FileAccessException("Falha ao excluir via Shizuku")
    }

    override suspend fun parent(location: FileLocation): FileLocation? =
        File(path(location)).parent?.let { FileLocation.Direct(it) }

    private fun decodeStat(line: String): FileNode {
        val parts = line.split('\t')
        if (parts.size != 6) throw FileAccessException("Resposta inválida do serviço privilegiado")
        fun text(index: Int) = String(decoder.decode(parts[index]), StandardCharsets.UTF_8)
        return FileNode(FileLocation.Direct(text(0)), text(1), parts[2] == "1", parts[3].toLong(),
            parts[4].toLong(), isSymlink = parts[5] == "1")
    }
}
