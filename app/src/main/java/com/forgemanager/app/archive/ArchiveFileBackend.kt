package com.forgemanager.app.archive

import android.content.Context
import com.forgemanager.app.core.file.BackendCapabilities
import com.forgemanager.app.core.file.FileAccessException
import com.forgemanager.app.core.file.FileBackend
import com.forgemanager.app.core.file.FileLocation
import com.forgemanager.app.core.file.FileNode
import com.forgemanager.app.core.security.PathSecurity
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ArchiveFileBackend(context: Context) : FileBackend {
    override val id: String = "archive"
    private val cacheDirectory = File(context.cacheDir, "archive-edits").apply { mkdirs() }

    override fun supports(location: FileLocation): Boolean = location is FileLocation.Archive
    private fun archive(location: FileLocation) = location as? FileLocation.Archive
        ?: throw FileAccessException("Local de arquivo compactado inválido")

    override suspend fun capabilities(location: FileLocation) = BackendCapabilities(read = true, write = true)

    override suspend fun list(location: FileLocation, showHidden: Boolean): List<FileNode> {
        val a = archive(location)
        val prefix = a.entryPath.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val nodes = LinkedHashMap<String, FileNode>()
        ZipFile(a.archivePath).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val safe = runCatching { ZipSecurity.normalizeEntryName(entry.name) }.getOrNull() ?: continue
                if (!safe.startsWith(prefix) || safe == prefix.trimEnd('/')) continue
                val remainder = safe.removePrefix(prefix)
                val first = remainder.substringBefore('/')
                if (first.isEmpty() || (!showHidden && first.startsWith('.'))) continue
                val childPath = prefix + first
                val implicitDirectory = remainder.contains('/') || entry.isDirectory
                nodes.putIfAbsent(first, FileNode(
                    FileLocation.Archive(a.archivePath, childPath), first, implicitDirectory,
                    if (implicitDirectory) 0 else entry.size.coerceAtLeast(0), entry.time.coerceAtLeast(0)
                ))
            }
        }
        return nodes.values.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    override suspend fun stat(location: FileLocation): FileNode {
        val a = archive(location)
        if (a.entryPath.isBlank()) return FileNode(a, File(a.archivePath).name, true)
        val name = a.entryPath.trim('/').substringAfterLast('/')
        ZipFile(a.archivePath).use { zip ->
            zip.getEntry(a.entryPath)?.let { return FileNode(a, name, it.isDirectory, it.size.coerceAtLeast(0), it.time.coerceAtLeast(0)) }
            val prefix = a.entryPath.trimEnd('/') + "/"
            if (zip.entries().asSequence().any { it.name.startsWith(prefix) }) return FileNode(a, name, true)
        }
        throw FileAccessException("Entrada não encontrada")
    }

    override suspend fun openInput(location: FileLocation): InputStream {
        val a = archive(location)
        val zip = ZipFile(a.archivePath)
        val entry = zip.getEntry(a.entryPath) ?: run { zip.close(); throw FileAccessException("Entrada não encontrada") }
        return object : FilterInputStream(zip.getInputStream(entry)) {
            override fun close() { try { super.close() } finally { zip.close() } }
        }
    }

    override suspend fun openOutput(location: FileLocation, truncate: Boolean): OutputStream {
        if (!truncate) throw FileAccessException("Anexar dados dentro de ZIP não é suportado")
        val a = archive(location)
        val temp = File.createTempFile("entry-", ".tmp", cacheDirectory)
        return object : FilterOutputStream(FileOutputStream(temp)) {
            override fun close() {
                super.close()
                try { rewrite(a.archivePath, replace = a.entryPath to temp) } finally { temp.delete() }
            }
        }
    }

    override suspend fun create(parent: FileLocation, name: String): FileNode {
        PathSecurity.validateName(name)
        val p = archive(parent)
        val entry = join(p.entryPath, name)
        val empty = File.createTempFile("empty-", ".tmp", cacheDirectory)
        try { rewrite(p.archivePath, add = entry to empty) } finally { empty.delete() }
        return stat(FileLocation.Archive(p.archivePath, entry))
    }

    override suspend fun mkdir(parent: FileLocation, name: String): FileNode {
        PathSecurity.validateName(name)
        val p = archive(parent)
        val entry = join(p.entryPath, name) + "/"
        rewrite(p.archivePath, newDirectory = entry)
        return FileNode(FileLocation.Archive(p.archivePath, entry.trimEnd('/')), name, true)
    }

    override suspend fun rename(source: FileLocation, newName: String): FileNode {
        PathSecurity.validateName(newName)
        val a = archive(source)
        val parent = a.entryPath.trim('/').substringBeforeLast('/', "")
        val target = join(parent, newName)
        rewrite(a.archivePath, rename = a.entryPath.trimEnd('/') to target)
        return stat(FileLocation.Archive(a.archivePath, target))
    }

    override suspend fun delete(location: FileLocation) {
        val a = archive(location)
        rewrite(a.archivePath, deletePrefix = a.entryPath.trimEnd('/'))
    }

    override suspend fun parent(location: FileLocation): FileLocation? {
        val a = archive(location)
        if (a.entryPath.isBlank()) return FileLocation.Direct(File(a.archivePath).parent ?: return null)
        return FileLocation.Archive(a.archivePath, a.entryPath.trim('/').substringBeforeLast('/', ""))
    }

    private fun rewrite(
        archivePath: String,
        replace: Pair<String, File>? = null,
        add: Pair<String, File>? = null,
        newDirectory: String? = null,
        rename: Pair<String, String>? = null,
        deletePrefix: String? = null
    ) {
        val original = File(archivePath)
        val temporary = File(original.parentFile, ".${original.name}.${System.nanoTime()}.tmp")
        try {
            ZipFile(original).use { zip ->
                FileOutputStream(temporary).use { raw ->
                    ZipOutputStream(raw.buffered(128 * 1024)).use { output ->
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val old = entries.nextElement()
                            val oldName = ZipSecurity.normalizeEntryName(old.name).let { if (old.isDirectory) "$it/" else it }
                            if (replace?.first == oldName) continue
                            if (deletePrefix != null && (oldName == deletePrefix || oldName.startsWith("$deletePrefix/"))) continue
                            val renamedName = if (rename != null && (oldName == rename.first || oldName.startsWith(rename.first + "/"))) {
                                rename.second + oldName.removePrefix(rename.first)
                            } else oldName
                            val next = if (renamedName == oldName) ZipEntry(old) else ZipEntry(renamedName).apply {
                                time = old.time
                                comment = old.comment
                                extra = old.extra
                            }
                            output.putNextEntry(next)
                            if (!old.isDirectory) zip.getInputStream(old).use { it.copyTo(output, 128 * 1024) }
                            output.closeEntry()
                        }
                        replace?.let { writeFileEntry(output, it.first, it.second) }
                        add?.let { writeFileEntry(output, it.first, it.second) }
                        newDirectory?.let { output.putNextEntry(ZipEntry(it)); output.closeEntry() }
                    }
                }
            }
            FileOutputStream(temporary, true).use { it.fd.sync() }
            try {
                Files.move(temporary.toPath(), original.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), original.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw FileAccessException("Falha ao atualizar arquivo compactado", error)
        }
    }

    private fun writeFileEntry(output: ZipOutputStream, name: String, source: File) {
        val safe = ZipSecurity.normalizeEntryName(name)
        output.putNextEntry(ZipEntry(safe))
        FileInputStream(source).use { it.copyTo(output, 128 * 1024) }
        output.closeEntry()
    }

    private fun join(parent: String, name: String) = listOf(parent.trim('/'), name).filter { it.isNotEmpty() }.joinToString("/")
}

private fun <T> java.util.Enumeration<T>.asSequence(): Sequence<T> = sequence {
    while (hasMoreElements()) yield(nextElement())
}
