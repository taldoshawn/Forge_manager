package com.forgemanager.app.core.file

import com.forgemanager.app.core.security.PathSecurity
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption

class DirectFileBackend : FileBackend {
    override val id: String = "direct"

    override fun supports(location: FileLocation): Boolean = location is FileLocation.Direct

    private fun file(location: FileLocation): File =
        File((location as? FileLocation.Direct)?.path ?: throw FileAccessException("Local inválido"))

    override suspend fun capabilities(location: FileLocation): BackendCapabilities {
        val f = file(location)
        return BackendCapabilities(read = f.canRead(), write = f.canWrite(), randomAccess = true)
    }

    override suspend fun list(location: FileLocation, showHidden: Boolean): List<FileNode> {
        val directory = file(location)
        if (!directory.isDirectory) throw FileAccessException("Não é uma pasta: ${directory.path}")
        val children = directory.listFiles() ?: throw FileAccessException("Sem permissão para listar ${directory.path}")
        return children.asSequence()
            .filter { showHidden || !it.name.startsWith('.') }
            .map(::toNode)
            .sortedWith(compareBy<FileNode>({ !it.isDirectory }, { it.name.lowercase() }, { it.name }))
            .toList()
    }

    override suspend fun stat(location: FileLocation): FileNode {
        val f = file(location)
        if (!f.exists() && !Files.isSymbolicLink(f.toPath())) throw FileAccessException("Arquivo não encontrado")
        return toNode(f)
    }

    override suspend fun openInput(location: FileLocation): InputStream =
        BufferedInputStream(FileInputStream(file(location)), BUFFER_SIZE)

    override suspend fun openOutput(location: FileLocation, truncate: Boolean): OutputStream =
        BufferedOutputStream(FileOutputStream(file(location), !truncate), BUFFER_SIZE)

    override suspend fun create(parent: FileLocation, name: String): FileNode {
        val target = PathSecurity.resolveChild(file(parent), name)
        if (!target.createNewFile()) throw FileAccessException("O arquivo já existe")
        return toNode(target)
    }

    override suspend fun mkdir(parent: FileLocation, name: String): FileNode {
        val target = PathSecurity.resolveChild(file(parent), name)
        if (!target.mkdir()) throw FileAccessException("Não foi possível criar a pasta")
        return toNode(target)
    }

    override suspend fun rename(source: FileLocation, newName: String): FileNode {
        val src = file(source)
        val target = PathSecurity.resolveChild(src.parentFile ?: throw FileAccessException("Sem pasta pai"), newName)
        if (target.exists()) throw FileAccessException("Já existe um item com esse nome")
        if (!src.renameTo(target)) throw FileAccessException("Falha ao renomear")
        return toNode(target)
    }

    override suspend fun delete(location: FileLocation) {
        deleteNoFollow(file(location))
    }

    override suspend fun parent(location: FileLocation): FileLocation? =
        file(location).parentFile?.let { FileLocation.Direct(it.path) }

    private fun deleteNoFollow(file: File) {
        if (Files.isSymbolicLink(file.toPath())) {
            if (!Files.deleteIfExists(file.toPath())) throw FileAccessException("Falha ao excluir link")
            return
        }
        if (file.isDirectory) file.listFiles()?.forEach(::deleteNoFollow)
        if (!file.delete()) throw FileAccessException("Falha ao excluir ${file.name}")
    }

    private fun toNode(file: File): FileNode {
        val isLink = Files.isSymbolicLink(file.toPath())
        val isDirectory = Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        return FileNode(
            location = FileLocation.Direct(file.path),
            name = file.name.ifEmpty { file.path },
            isDirectory = isDirectory,
            size = if (isDirectory) 0 else file.length(),
            modified = file.lastModified(),
            isSymlink = isLink
        )
    }

    companion object { private const val BUFFER_SIZE = 128 * 1024 }
}
