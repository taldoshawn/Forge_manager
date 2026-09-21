package com.forgemanager.app.features.apktools

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ApkPackageTools {
    data class ExtractResult(val directory: File, val files: Int, val bytes: Long)
    data class RepackResult(val apk: File, val entries: Int, val bytes: Long)
    data class XapkResult(val apk: File, val splitCount: Int, val selectedEntry: String)

    /** Extracts an APK/ZIP without allowing Zip-Slip, absolute paths or link escapes. */
    fun extractArchive(source: File, destination: File): ExtractResult {
        require(source.isFile) { "Pacote não encontrado" }
        if (destination.exists()) require(destination.isDirectory) { "Destino não é uma pasta" }
        else require(destination.mkdirs()) { "Não foi possível criar a pasta de destino" }
        val canonicalRoot = destination.canonicalFile
        var count = 0
        var total = 0L
        ZipFile(source).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                require(++count <= MAX_ENTRIES) { "Pacote possui entradas demais" }
                val safeName = normalizeEntry(entry.name)
                if (safeName.isEmpty()) continue
                val target = File(canonicalRoot, safeName).canonicalFile
                require(isInside(canonicalRoot, target)) { "Entrada tenta sair da pasta: ${entry.name}" }
                if (entry.isDirectory) {
                    require(target.isDirectory || target.mkdirs()) { "Falha ao criar ${entry.name}" }
                    continue
                }
                val declared = entry.size
                require(declared < 0 || declared <= MAX_ENTRY_BYTES) { "Entrada grande demais: ${entry.name}" }
                require(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true) { "Falha ao criar pasta pai" }
                zip.getInputStream(entry).use { input ->
                    BufferedOutputStream(FileOutputStream(target), BUFFER).use { output ->
                        val buffer = ByteArray(BUFFER)
                        var entryBytes = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            entryBytes += read
                            total += read
                            require(entryBytes <= MAX_ENTRY_BYTES) { "Entrada excede limite: ${entry.name}" }
                            require(total <= MAX_TOTAL_BYTES) { "Pacote excede limite seguro de extração" }
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
        }
        return ExtractResult(canonicalRoot, count, total)
    }

    /** Rebuilds a directory as an unsigned APK. Symbolic links are never followed. */
    fun repackDirectory(sourceDirectory: File, outputApk: File): RepackResult {
        val root = sourceDirectory.canonicalFile
        require(root.isDirectory) { "Pasta descompilada não encontrada" }
        require(!Files.isSymbolicLink(root.toPath())) { "Pasta de origem não pode ser link simbólico" }
        val temp = File(outputApk.parentFile ?: root.parentFile, ".${outputApk.name}.${System.nanoTime()}.tmp")
        var count = 0
        var total = 0L
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temp), BUFFER)).use { zip ->
                root.walkTopDown().onEnter { dir ->
                    !Files.isSymbolicLink(dir.toPath()) && isInside(root, dir.canonicalFile)
                }.filter { it != root }.forEach { file ->
                    require(++count <= MAX_ENTRIES) { "Pasta possui arquivos demais" }
                    require(!Files.isSymbolicLink(file.toPath())) { "Link simbólico bloqueado: ${file.name}" }
                    val canonical = file.canonicalFile
                    require(isInside(root, canonical)) { "Arquivo fora da pasta de origem" }
                    val name = canonical.relativeTo(root).invariantSeparatorsPath
                    require(name.isNotBlank() && !name.startsWith('/'))
                    if (canonical.isDirectory) {
                        zip.putNextEntry(ZipEntry("$name/"))
                        zip.closeEntry()
                    } else if (canonical.isFile) {
                        require(canonical.length() <= MAX_ENTRY_BYTES) { "Arquivo grande demais: $name" }
                        total += canonical.length()
                        require(total <= MAX_TOTAL_BYTES) { "Projeto reconstruído excede limite seguro" }
                        val entry = ZipEntry(name).apply { time = canonical.lastModified().coerceAtLeast(0L) }
                        zip.putNextEntry(entry)
                        BufferedInputStream(FileInputStream(canonical), BUFFER).use { it.copyTo(zip, BUFFER) }
                        zip.closeEntry()
                    }
                }
            }
            require(temp.isFile && temp.length() > 0) { "APK reconstruído vazio" }
            if (outputApk.exists()) require(outputApk.delete()) { "Não foi possível substituir a saída" }
            require(temp.renameTo(outputApk) || runCatching {
                temp.copyTo(outputApk, overwrite = true)
                temp.delete()
                true
            }.getOrDefault(false)) { "Falha ao mover APK reconstruído" }
            return RepackResult(outputApk, count, outputApk.length())
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /**
     * Converts an XAPK/APKM/APKS to the contained base/universal APK. If the
     * package has split APKs, this does not magically merge split resources;
     * it chooses base.apk (or the largest APK when base is absent).
     */
    fun xapkToApk(source: File, outputApk: File): XapkResult {
        require(source.isFile) { "XAPK não encontrado" }
        ZipFile(source).use { zip ->
            val candidates = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.lowercase(Locale.ROOT).endsWith(".apk") }
                .toList()
            require(candidates.isNotEmpty()) { "Nenhum APK encontrado dentro do pacote" }
            val selected = candidates.firstOrNull { it.name.substringAfterLast('/').equals("base.apk", true) }
                ?: candidates.firstOrNull { it.name.substringAfterLast('/').contains("universal", true) }
                ?: candidates.maxByOrNull { it.size.coerceAtLeast(0) }!!
            require(selected.size < 0 || selected.size <= MAX_ENTRY_BYTES) { "APK interno grande demais" }
            val temp = File(outputApk.parentFile ?: source.parentFile, ".${outputApk.name}.${System.nanoTime()}.tmp")
            try {
                zip.getInputStream(selected).use { input ->
                    BufferedOutputStream(FileOutputStream(temp), BUFFER).use { output ->
                        val buffer = ByteArray(BUFFER)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= MAX_ENTRY_BYTES) { "APK interno excede limite" }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                require(temp.length() > 0) { "APK extraído vazio" }
                if (outputApk.exists()) outputApk.delete()
                require(temp.renameTo(outputApk) || runCatching {
                    temp.copyTo(outputApk, overwrite = true); temp.delete(); true
                }.getOrDefault(false)) { "Falha ao criar APK" }
            } finally {
                if (temp.exists()) temp.delete()
            }
            return XapkResult(outputApk, candidates.size, selected.name)
        }
    }

    private fun normalizeEntry(raw: String): String {
        require(raw.isNotBlank()) { "Entrada ZIP sem nome" }
        require(!raw.startsWith('/') && !raw.startsWith('\\')) { "Caminho absoluto bloqueado" }
        require(!Regex("^[A-Za-z]:").containsMatchIn(raw)) { "Caminho absoluto Windows bloqueado" }
        val parts = raw.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        require(parts.none { it == ".." }) { "Traversal bloqueado: $raw" }
        require(parts.none { '\u0000' in it }) { "Nome inválido" }
        return parts.joinToString("/")
    }

    private fun isInside(root: File, child: File): Boolean {
        val rootPath = root.path.trimEnd(File.separatorChar) + File.separator
        val childPath = child.path
        return child == root || childPath.startsWith(rootPath)
    }

    private fun <T> java.util.Enumeration<T>.asSequence(): Sequence<T> = sequence {
        while (hasMoreElements()) yield(nextElement())
    }

    private const val BUFFER = 128 * 1024
    private const val MAX_ENTRIES = 100_000
    private const val MAX_ENTRY_BYTES = 1024L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024
}
