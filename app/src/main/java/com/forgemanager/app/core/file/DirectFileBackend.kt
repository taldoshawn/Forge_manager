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

    /** First candidate that exists or is readable; always prefers a bypass form for restricted trees. */
    private fun file(location: FileLocation): File {
        val raw = (location as? FileLocation.Direct)?.path ?: throw FileAccessException("Local inválido")
        return File(PathSecurity.openablePath(raw))
    }

    private fun fileForWrite(location: FileLocation): File {
        val raw = (location as? FileLocation.Direct)?.path ?: throw FileAccessException("Local inválido")
        return File(PathSecurity.openablePath(raw, write = true))
    }

    /** Try every bypass candidate until listFiles succeeds. */
    private fun openDirectory(location: FileLocation): File {
        val raw = (location as? FileLocation.Direct)?.path ?: throw FileAccessException("Local inválido")
        var lastError: Exception? = null
        for (candidate in PathSecurity.bypassCandidates(raw)) {
            val dir = File(candidate)
            try {
                if (dir.isDirectory) {
                    val children = dir.listFiles()
                    if (children != null) return dir
                }
            } catch (e: SecurityException) {
                lastError = e
            }
        }
        // Last resort: primary bypass form even if probe failed.
        val fallback = File(PathSecurity.maybeBypassRestricted(raw))
        if (fallback.isDirectory && fallback.listFiles() != null) return fallback
        throw FileAccessException(
            lastError?.message ?: "Sem permissão para listar ${PathSecurity.stripIgnorables(raw)}"
        )
    }

    override suspend fun capabilities(location: FileLocation): BackendCapabilities {
        val f = file(location)
        return BackendCapabilities(read = f.canRead(), write = f.canWrite(), randomAccess = true)
    }

    override suspend fun list(location: FileLocation, showHidden: Boolean): List<FileNode> {
        val directory = openDirectory(location)
        val children = directory.listFiles()
            ?: throw FileAccessException("Sem permissão para listar ${directory.path}")
        val parentWorking = directory.path.trimEnd('/')
        return children.asSequence()
            .filter { showHidden || !it.name.startsWith('.') }
            .map { child -> toNodeUnder(parentWorking, child) }
            .sortedWith(compareBy<FileNode>({ !it.isDirectory }, { it.name.lowercase() }, { it.name }))
            .toList()
    }

    override suspend fun stat(location: FileLocation): FileNode {
        val f = file(location)
        if (!f.exists() && !Files.isSymbolicLink(f.toPath())) {
            // Retry with every candidate before giving up.
            for (candidate in PathSecurity.bypassCandidates(
                (location as FileLocation.Direct).path
            )) {
                val alt = File(candidate)
                if (alt.exists() || Files.isSymbolicLink(alt.toPath())) return toNode(alt)
            }
            throw FileAccessException("Arquivo não encontrado")
        }
        return toNode(f)
    }

    override suspend fun openInput(location: FileLocation): InputStream =
        BufferedInputStream(FileInputStream(file(location)), BUFFER_SIZE)

    override suspend fun openOutput(location: FileLocation, truncate: Boolean): OutputStream =
        BufferedOutputStream(FileOutputStream(fileForWrite(location), !truncate), BUFFER_SIZE)

    override suspend fun create(parent: FileLocation, name: String): FileNode {
        val target = PathSecurity.resolveChild(fileForWrite(parent), name)
        if (!target.createNewFile()) throw FileAccessException("O arquivo já existe")
        return toNode(target)
    }

    override suspend fun mkdir(parent: FileLocation, name: String): FileNode {
        val target = PathSecurity.resolveChild(fileForWrite(parent), name)
        if (!target.mkdir()) throw FileAccessException("Não foi possível criar a pasta")
        return toNode(target)
    }

    override suspend fun rename(source: FileLocation, newName: String): FileNode {
        val src = file(source)
        val parent = src.parentFile ?: throw FileAccessException("Sem pasta pai")
        val target = PathSecurity.resolveChild(parent, newName)
        if (target.exists()) throw FileAccessException("Já existe um item com esse nome")
        if (!src.renameTo(target)) throw FileAccessException("Falha ao renomear")
        return toNode(target)
    }

    override suspend fun delete(location: FileLocation) {
        deleteNoFollow(file(location))
    }

    override suspend fun parent(location: FileLocation): FileLocation? {
        val f = file(location)
        val parent = f.parentFile ?: return null
        return FileLocation.Direct(PathSecurity.maybeBypassRestricted(parent.path))
    }

    private fun deleteNoFollow(file: File) {
        if (Files.isSymbolicLink(file.toPath())) {
            if (!Files.deleteIfExists(file.toPath())) throw FileAccessException("Falha ao excluir link")
            return
        }
        if (file.isDirectory) file.listFiles()?.forEach(::deleteNoFollow)
        if (!file.delete()) throw FileAccessException("Falha ao excluir ${file.name}")
    }

    private fun toNodeUnder(parentWorkingPath: String, child: File): FileNode {
        val isLink = Files.isSymbolicLink(child.toPath())
        val isDirectory = Files.isDirectory(child.toPath(), LinkOption.NOFOLLOW_LINKS)
        // Keep the working bypass prefix so every deeper click still carries the alias.
        val forcedPath = PathSecurity.maybeBypassRestricted("$parentWorkingPath/${child.name}")
        return FileNode(
            location = FileLocation.Direct(forcedPath),
            name = child.name.ifEmpty { child.path },
            isDirectory = isDirectory,
            size = if (isDirectory) 0 else child.length(),
            modified = child.lastModified(),
            isSymlink = isLink
        )
    }

    private fun toNode(file: File): FileNode {
        val isLink = Files.isSymbolicLink(file.toPath())
        val isDirectory = Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        val path = PathSecurity.maybeBypassRestricted(file.path)
        return FileNode(
            location = FileLocation.Direct(path),
            name = file.name.ifEmpty { file.path },
            isDirectory = isDirectory,
            size = if (isDirectory) 0 else file.length(),
            modified = file.lastModified(),
            isSymlink = isLink
        )
    }

    companion object { private const val BUFFER_SIZE = 128 * 1024 }
}
