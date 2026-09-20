package com.forgemanager.app.core.file

import com.forgemanager.app.core.security.PathSecurity
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext

class FileOperations(private val resolver: AccessResolver) {
    suspend fun copy(
        sources: List<FileNode>,
        destination: FileLocation,
        conflictPolicy: ConflictPolicy,
        progress: ProgressListener? = null
    ): List<FileNode> {
        val result = ArrayList<FileNode>()
        var bytesDone = 0L
        val totalBytes = sources.sumOf { if (it.isDirectory) 0 else it.size }
        sources.forEachIndexed { index, source ->
            coroutineContext.ensureActive()
            val copied = copyOne(source, destination, conflictPolicy) { delta ->
                bytesDone += delta
                progress?.onProgress(OperationProgress(index, sources.size, bytesDone, totalBytes, source.name))
            }
            if (copied != null) result += copied
            progress?.onProgress(OperationProgress(index + 1, sources.size, bytesDone, totalBytes, source.name))
        }
        return result
    }

    suspend fun move(
        sources: List<FileNode>,
        destination: FileLocation,
        conflictPolicy: ConflictPolicy,
        progress: ProgressListener? = null
    ): List<FileNode> {
        val copied = copy(sources, destination, conflictPolicy, progress)
        if (copied.size == sources.size) sources.forEach { resolver.backendFor(it.location, write = true).delete(it.location) }
        return copied
    }

    private suspend fun copyOne(
        source: FileNode,
        destination: FileLocation,
        policy: ConflictPolicy,
        onBytes: (Long) -> Unit
    ): FileNode? {
        val sourceBackend = resolver.backendFor(source.location)
        val destinationBackend = resolver.backendFor(destination, write = true)
        var name = source.name
        val existing = destinationBackend.list(destination).associateBy { it.name }
        if (existing.containsKey(name)) {
            when (policy) {
                ConflictPolicy.SKIP -> return null
                ConflictPolicy.ASK -> throw FileAccessException("Conflito: $name")
                ConflictPolicy.REPLACE -> destinationBackend.delete(existing.getValue(name).location)
                ConflictPolicy.RENAME -> name = uniqueName(name, existing.keys)
            }
        }
        if (source.isSymlink) throw FileAccessException("Links simbólicos não são copiados automaticamente")
        if (source.isDirectory) {
            val target = destinationBackend.mkdir(destination, name)
            sourceBackend.list(source.location).forEach { copyOne(it, target.location, policy, onBytes) }
            return target
        }
        val target = destinationBackend.create(destination, name)
        try {
            sourceBackend.openInput(source.location).use { input ->
                destinationBackend.openOutput(target.location).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        onBytes(count.toLong())
                    }
                    output.flush()
                }
            }
        } catch (error: Throwable) {
            runCatching { destinationBackend.delete(target.location) }
            throw error
        }
        return destinationBackend.stat(target.location)
    }

    suspend fun swapNames(first: FileNode, second: FileNode, parent: FileLocation) {
        require(first.location != second.location) { "Selecione dois itens diferentes" }
        val firstBackend = resolver.backendFor(first.location, write = true)
        val secondBackend = resolver.backendFor(second.location, write = true)
        if (firstBackend.id != secondBackend.id) throw FileAccessException("Os itens precisam usar o mesmo backend")
        val existing = resolver.backendFor(parent).list(parent, showHidden = true).mapTo(HashSet()) { it.name }
        var tempName: String
        do tempName = ".forge-swap-${System.nanoTime()}" while (tempName in existing)
        PathSecurity.validateName(tempName)

        val temporary = firstBackend.rename(first.location, tempName)
        try {
            val secondMoved = secondBackend.rename(second.location, first.name)
            try {
                firstBackend.rename(temporary.location, second.name)
            } catch (error: Throwable) {
                runCatching { secondBackend.rename(secondMoved.location, second.name) }
                runCatching { firstBackend.rename(temporary.location, first.name) }
                throw error
            }
        } catch (error: Throwable) {
            runCatching { firstBackend.rename(temporary.location, first.name) }
            throw error
        }
    }

    private fun uniqueName(name: String, existing: Set<String>): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var number = 1
        var candidate: String
        do candidate = "$base ($number)${extension}" while (candidate in existing && ++number < 10_000)
        PathSecurity.validateName(candidate)
        return candidate
    }
}
