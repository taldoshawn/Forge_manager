package com.forgemanager.app.archive

import com.forgemanager.app.core.file.AccessResolver
import com.forgemanager.app.core.file.ConflictPolicy
import com.forgemanager.app.core.file.FileAccessException
import com.forgemanager.app.core.file.FileLocation
import com.forgemanager.app.core.file.FileNode
import com.forgemanager.app.core.security.PathSecurity
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

class ZipOperations(private val resolver: AccessResolver) {
    suspend fun createZip(
        sources: List<FileNode>,
        destination: FileLocation,
        requestedName: String,
        conflictPolicy: ConflictPolicy = ConflictPolicy.RENAME,
        onItem: ((String) -> Unit)? = null
    ): FileNode? {
        if (sources.isEmpty()) throw FileAccessException("Selecione pelo menos um item")
        val name = requestedName.trim().let { if (it.endsWith(".zip", true)) it else "$it.zip" }
        PathSecurity.validateName(name)

        val destinationBackend = resolver.backendFor(destination, write = true)
        val existing = destinationBackend.list(destination, showHidden = true).associateBy { it.name }
        var targetName = name
        var replaceTarget: FileNode? = null
        existing[targetName]?.let { collision ->
            when (conflictPolicy) {
                ConflictPolicy.ASK -> throw FileAccessException("Já existe um item chamado $targetName")
                ConflictPolicy.SKIP -> return null
                ConflictPolicy.REPLACE -> {
                    if (collision.isDirectory) throw FileAccessException("Não é seguro substituir uma pasta por um ZIP")
                    replaceTarget = collision
                }
                ConflictPolicy.RENAME -> targetName = uniqueName(targetName, existing.keys)
            }
        }

        blockOutputInsideSource(sources, destination, targetName)
        val workingName = if (replaceTarget != null) uniqueTemporaryName(existing.keys) else targetName
        val working = destinationBackend.create(destination, workingName)
        val visited = HashSet<String>()
        var entries = 0
        try {
            destinationBackend.openOutput(working.location, truncate = true).use { raw ->
                ZipOutputStream(raw.buffered(BUFFER_SIZE)).use { zip ->
                    for (source in sources) {
                        addNode(zip, source, source.name, visited, 0, onItem) { entries++ }
                    }
                }
            }
        } catch (error: Throwable) {
            runCatching { destinationBackend.delete(working.location) }
            if (error is FileAccessException) throw error
            throw FileAccessException("Falha ao criar arquivo ZIP", error)
        }
        if (entries == 0) {
            runCatching { destinationBackend.delete(working.location) }
            throw FileAccessException("Nenhum item pôde ser adicionado ao ZIP")
        }
        if (replaceTarget != null) {
            destinationBackend.delete(replaceTarget!!.location)
            return try {
                destinationBackend.rename(working.location, targetName)
            } catch (error: Throwable) {
                throw FileAccessException("O ZIP foi criado, mas não foi possível concluir a substituição", error)
            }
        }
        return destinationBackend.stat(working.location)
    }

    private suspend fun addNode(
        zip: ZipOutputStream,
        node: FileNode,
        entryName: String,
        visited: MutableSet<String>,
        depth: Int,
        onItem: ((String) -> Unit)?,
        onEntry: () -> Unit
    ) {
        coroutineContext.ensureActive()
        if (depth > MAX_DEPTH) throw FileAccessException("Profundidade de pastas excessiva ao compactar")
        if (node.isSymlink) throw FileAccessException("Links simbólicos não são compactados automaticamente: ${node.name}")
        if (!visited.add(node.location.displayPath)) throw FileAccessException("Ciclo de pastas detectado ao compactar")
        val safeName = ZipSecurity.normalizeEntryName(entryName)
        onItem?.invoke(node.name)
        val backend = resolver.backendFor(node.location)
        if (node.isDirectory) {
            val directoryEntry = ZipEntry(safeName.trimEnd('/') + "/").apply {
                if (node.modified > 0) time = node.modified
            }
            zip.putNextEntry(directoryEntry)
            zip.closeEntry()
            onEntry()
            val children = backend.list(node.location, showHidden = true)
            for (child in children) addNode(zip, child, "$safeName/${child.name}", visited, depth + 1, onItem, onEntry)
        } else {
            val entry = ZipEntry(safeName).apply { if (node.modified > 0) time = node.modified }
            zip.putNextEntry(entry)
            backend.openInput(node.location).use { input -> input.copyTo(zip, BUFFER_SIZE) }
            zip.closeEntry()
            onEntry()
        }
        visited.remove(node.location.displayPath)
    }

    private fun blockOutputInsideSource(sources: List<FileNode>, destination: FileLocation, targetName: String) {
        val destinationPath = (destination as? FileLocation.Direct)?.path ?: return
        val output = normalizedOrCanonical(File(destinationPath, targetName))
        sources.filter { it.isDirectory }.forEach { source ->
            val sourcePath = (source.location as? FileLocation.Direct)?.path ?: return@forEach
            val root = normalizedOrCanonical(File(sourcePath))
            if (output.path == root.path || output.path.startsWith(root.path + File.separator)) {
                throw FileAccessException("O ZIP de destino não pode ficar dentro de uma pasta que está sendo compactada")
            }
        }
    }

    private fun normalizedOrCanonical(file: File): File =
        runCatching { file.canonicalFile }.getOrElse { file.absoluteFile.toPath().normalize().toFile() }

    private fun uniqueTemporaryName(existing: Set<String>): String {
        repeat(100) {
            val candidate = ".forge-zip-${System.nanoTime()}-$it.tmp"
            if (candidate !in existing) {
                PathSecurity.validateName(candidate)
                return candidate
            }
        }
        throw FileAccessException("Não foi possível criar um nome temporário seguro")
    }

    private fun uniqueName(name: String, existing: Set<String>): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        for (number in 1..9999) {
            val candidate = "$base ($number)$extension"
            if (candidate !in existing) {
                PathSecurity.validateName(candidate)
                return candidate
            }
        }
        throw FileAccessException("Não foi possível gerar um nome livre para o arquivo ZIP")
    }

    companion object {
        private const val BUFFER_SIZE = 128 * 1024
        private const val MAX_DEPTH = 128
    }
}
