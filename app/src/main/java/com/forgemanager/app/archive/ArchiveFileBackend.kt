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
            val budget = ArchiveBudget()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                budget.accept(entry)
                val safe = ZipSecurity.normalizeEntryName(entry.name)
                if (!safe.startsWith(prefix) || safe == prefix.trimEnd('/')) continue
                val remainder = safe.removePrefix(prefix)
                val first = remainder.substringBefore('/')
                if (first.isEmpty() || (!showHidden && first.startsWith('.'))) continue
                val childPath = prefix + first
                val implicitDirectory = remainder.contains('/') || entry.isDirectory
                nodes.putIfAbsent(first, FileNode(
                    FileLocation.Archive(a.archivePath, childPath), first, implicitDirectory,
                    if (implicitDirectory) 0 else safeSize(entry), entry.time.coerceAtLeast(0)
                ))
            }
        }
        return nodes.values.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    override suspend fun stat(location: FileLocation): FileNode {
        val a = archive(location)
        if (a.entryPath.isBlank()) return FileNode(a, File(a.archivePath).name, true)
        val requested = ZipSecurity.normalizeEntryName(a.entryPath)
        val name = requested.substringAfterLast('/')
        ZipFile(a.archivePath).use { zip ->
            val budget = ArchiveBudget()
            val entries = zip.entries()
            var implicitDirectory = false
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                budget.accept(entry)
                val safe = ZipSecurity.normalizeEntryName(entry.name)
                if (safe == requested) {
                    return FileNode(a, name, entry.isDirectory, if (entry.isDirectory) 0 else safeSize(entry), entry.time.coerceAtLeast(0))
                }
                if (safe.startsWith(requested.trimEnd('/') + "/")) implicitDirectory = true
            }
            if (implicitDirectory) return FileNode(a, name, true)
        }
        throw FileAccessException("Entrada não encontrada")
    }

    override suspend fun openInput(location: FileLocation): InputStream {
        val a = archive(location)
        val requested = ZipSecurity.normalizeEntryName(a.entryPath)
        val zip = ZipFile(a.archivePath)
        val entry = zip.entries().asSequence().firstOrNull {
            runCatching { ZipSecurity.normalizeEntryName(it.name) == requested }.getOrDefault(false)
        } ?: run { zip.close(); throw FileAccessException("Entrada não encontrada") }
        validateEntry(entry)
        val bounded = BoundedArchiveInputStream(zip.getInputStream(entry), MAX_ENTRY_BYTES)
        return object : FilterInputStream(bounded) {
            override fun close() { try { super.close() } finally { zip.close() } }
        }
    }

    override suspend fun openOutput(location: FileLocation, truncate: Boolean): OutputStream {
        if (!truncate) throw FileAccessException("Anexar dados dentro de ZIP não é suportado")
        val a = archive(location)
        val safeEntry = ZipSecurity.normalizeEntryName(a.entryPath)
        require(safeEntry.isNotBlank()) { "Entrada ZIP inválida" }
        val temp = File.createTempFile("entry-", ".tmp", cacheDirectory)
        return object : FilterOutputStream(FileOutputStream(temp)) {
            private var written = 0L
            override fun write(b: Int) {
                written++
                if (written > MAX_ENTRY_BYTES) throw FileAccessException("Entrada excede o limite seguro")
                super.write(b)
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                written += len
                if (written > MAX_ENTRY_BYTES) throw FileAccessException("Entrada excede o limite seguro")
                out.write(b, off, len)
            }
            override fun close() {
                try {
                    super.close()
                    rewrite(a.archivePath, replace = safeEntry to temp)
                } finally {
                    temp.delete()
                }
            }
        }
    }

    override suspend fun create(parent: FileLocation, name: String): FileNode {
        PathSecurity.validateName(name)
        val p = archive(parent)
        val entry = ZipSecurity.normalizeEntryName(join(p.entryPath, name))
        val empty = File.createTempFile("empty-", ".tmp", cacheDirectory)
        try { rewrite(p.archivePath, add = entry to empty) } finally { empty.delete() }
        return stat(FileLocation.Archive(p.archivePath, entry))
    }

    override suspend fun mkdir(parent: FileLocation, name: String): FileNode {
        PathSecurity.validateName(name)
        val p = archive(parent)
        val entry = ZipSecurity.normalizeEntryName(join(p.entryPath, name)) + "/"
        rewrite(p.archivePath, newDirectory = entry)
        return FileNode(FileLocation.Archive(p.archivePath, entry.trimEnd('/')), name, true)
    }

    override suspend fun rename(source: FileLocation, newName: String): FileNode {
        PathSecurity.validateName(newName)
        val a = archive(source)
        val sourceEntry = ZipSecurity.normalizeEntryName(a.entryPath.trimEnd('/'))
        val parent = sourceEntry.substringBeforeLast('/', "")
        val target = ZipSecurity.normalizeEntryName(join(parent, newName))
        rewrite(a.archivePath, rename = sourceEntry to target)
        return stat(FileLocation.Archive(a.archivePath, target))
    }

    override suspend fun delete(location: FileLocation) {
        val a = archive(location)
        val target = ZipSecurity.normalizeEntryName(a.entryPath.trimEnd('/'))
        require(target.isNotBlank()) { "Exclusão da raiz do arquivo compactado bloqueada" }
        rewrite(a.archivePath, deletePrefix = target)
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
        require(original.isFile) { "Arquivo compactado não encontrado" }
        replace?.second?.let { require(it.length() <= MAX_ENTRY_BYTES) { "Entrada excede o limite seguro" } }
        add?.second?.let { require(it.length() <= MAX_ENTRY_BYTES) { "Entrada excede o limite seguro" } }
        val temporary = File(original.parentFile, ".${original.name}.${System.nanoTime()}.forge.tmp")
        val oldMode = runCatching { android.system.Os.stat(original.path).st_mode and 0xFFF }.getOrNull()
        try {
            ZipFile(original).use { zip ->
                val budget = ArchiveBudget()
                FileOutputStream(temporary).use { raw ->
                    ZipOutputStream(raw.buffered(BUFFER_SIZE)).use { output ->
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val old = entries.nextElement()
                            budget.accept(old)
                            val normalized = ZipSecurity.normalizeEntryName(old.name)
                            val oldName = if (old.isDirectory) "$normalized/" else normalized
                            if (replace?.first == oldName || replace?.first == normalized) continue
                            if (deletePrefix != null && (normalized == deletePrefix || normalized.startsWith("$deletePrefix/"))) continue
                            val renamedName = if (rename != null && (normalized == rename.first || normalized.startsWith(rename.first + "/"))) {
                                ZipSecurity.normalizeEntryName(rename.second + normalized.removePrefix(rename.first)) + if (old.isDirectory) "/" else ""
                            } else oldName
                            val next = if (renamedName == oldName) ZipEntry(old) else ZipEntry(renamedName).apply {
                                time = old.time
                                comment = old.comment
                                extra = old.extra
                            }
                            output.putNextEntry(next)
                            if (!old.isDirectory) zip.getInputStream(old).use { copyBounded(it, output, MAX_ENTRY_BYTES) }
                            output.closeEntry()
                        }
                        replace?.let { writeFileEntry(output, it.first, it.second) }
                        add?.let { writeFileEntry(output, it.first, it.second) }
                        newDirectory?.let {
                            val safe = ZipSecurity.normalizeEntryName(it).trimEnd('/') + "/"
                            output.putNextEntry(ZipEntry(safe)); output.closeEntry()
                        }
                    }
                    raw.fd.sync()
                }
            }
            // Re-open the produced ZIP before touching the original. This catches corrupt rewrites.
            ZipFile(temporary).use { zip ->
                val budget = ArchiveBudget()
                zip.entries().asSequence().forEach { budget.accept(it) }
            }
            try {
                Files.move(temporary.toPath(), original.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), original.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            if (oldMode != null) runCatching { android.system.Os.chmod(original.path, oldMode) }
        } catch (error: Throwable) {
            temporary.delete()
            if (error is FileAccessException) throw error
            throw FileAccessException("Falha ao atualizar arquivo compactado", error)
        } finally {
            temporary.delete()
        }
    }

    private fun writeFileEntry(output: ZipOutputStream, name: String, source: File) {
        val safe = ZipSecurity.normalizeEntryName(name)
        require(safe.isNotBlank()) { "Nome de entrada ZIP inválido" }
        require(source.length() <= MAX_ENTRY_BYTES) { "Entrada excede o limite seguro" }
        output.putNextEntry(ZipEntry(safe))
        FileInputStream(source).use { copyBounded(it, output, MAX_ENTRY_BYTES) }
        output.closeEntry()
    }

    private fun validateEntry(entry: ZipEntry) {
        val safe = ZipSecurity.normalizeEntryName(entry.name)
        require(safe.isNotBlank()) { "Entrada ZIP inválida" }
        val size = entry.size
        require(size < 0 || size <= MAX_ENTRY_BYTES) { "Entrada compactada grande demais" }
        val compressed = entry.compressedSize
        if (size > MIN_RATIO_CHECK_BYTES && compressed > 0) {
            require(size / compressed.coerceAtLeast(1) <= MAX_COMPRESSION_RATIO) { "Taxa de compressão suspeita bloqueada" }
        }
    }

    private fun safeSize(entry: ZipEntry): Long {
        validateEntry(entry)
        return entry.size.coerceAtLeast(0)
    }

    private fun copyBounded(input: InputStream, output: OutputStream, limit: Long): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw FileAccessException("Entrada excede o limite seguro descompactado")
            output.write(buffer, 0, count)
        }
        return total
    }

    private fun join(parent: String, name: String) = listOf(parent.trim('/'), name).filter { it.isNotEmpty() }.joinToString("/")

    private class ArchiveBudget {
        private var entries = 0
        private var totalDeclared = 0L

        fun accept(entry: ZipEntry) {
            entries++
            if (entries > MAX_ARCHIVE_ENTRIES) throw FileAccessException("Arquivo compactado possui entradas demais")
            val safe = ZipSecurity.normalizeEntryName(entry.name)
            if (safe.isBlank() && !entry.isDirectory) throw FileAccessException("Entrada ZIP inválida")
            val size = entry.size
            if (size >= 0) {
                if (size > MAX_ENTRY_BYTES) throw FileAccessException("Entrada compactada grande demais: $safe")
                totalDeclared += size
                if (totalDeclared > MAX_TOTAL_UNCOMPRESSED) throw FileAccessException("Arquivo compactado excede o limite seguro descompactado")
            }
            val compressed = entry.compressedSize
            if (size > MIN_RATIO_CHECK_BYTES && compressed > 0 && size / compressed.coerceAtLeast(1) > MAX_COMPRESSION_RATIO) {
                throw FileAccessException("Taxa de compressão suspeita bloqueada: $safe")
            }
        }
    }

    private class BoundedArchiveInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var total = 0L
        override fun read(): Int {
            val value = super.read()
            if (value >= 0) account(1)
            return value
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val count = super.read(b, off, len)
            if (count > 0) account(count)
            return count
        }
        private fun account(count: Int) {
            total += count
            if (total > limit) throw FileAccessException("Entrada excede o limite seguro descompactado")
        }
    }

    companion object {
        private const val BUFFER_SIZE = 128 * 1024
        private const val MAX_ARCHIVE_ENTRIES = 100_000
        private const val MAX_ENTRY_BYTES = 512L * 1024 * 1024
        private const val MAX_TOTAL_UNCOMPRESSED = 2L * 1024 * 1024 * 1024
        private const val MIN_RATIO_CHECK_BYTES = 8L * 1024 * 1024
        private const val MAX_COMPRESSION_RATIO = 250L
    }
}

private fun <T> java.util.Enumeration<T>.asSequence(): Sequence<T> = sequence {
    while (hasMoreElements()) yield(nextElement())
}
