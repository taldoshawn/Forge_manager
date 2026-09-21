package com.forgemanager.app.features.dex

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.zip.ZipFile

data class DexClassInfo(
    val descriptor: String,
    val superclass: String?,
    val interfaces: List<String>,
    val accessFlags: Long
)

data class DexSummary(
    val name: String,
    val strings: List<String>,
    val types: List<String>,
    val classes: List<String>,
    val methods: List<String>,
    val fields: List<String>,
    val classInfo: Map<String, DexClassInfo> = emptyMap()
)

class DexWorkspace private constructor(
    val dexFiles: List<DexSummary>,
    private val temporaryFiles: List<File>
) : Closeable {
    fun search(query: String, regex: Boolean = false): List<String> {
        val matcher: (String) -> Boolean = if (regex) {
            val r = Regex(query, RegexOption.IGNORE_CASE); { r.containsMatchIn(it) }
        } else { { it.contains(query, ignoreCase = true) } }
        return dexFiles.flatMap { dex ->
            (dex.classes + dex.methods + dex.fields + dex.strings).asSequence()
                .filter(matcher)
                .take(2_000)
                .map { "[${dex.name}] $it" }
                .toList()
        }
    }

    override fun close() { temporaryFiles.forEach { it.delete() } }

    companion object {
        fun open(source: File, cacheDir: File): DexWorkspace = open(listOf(source), cacheDir)

        /** Opens several standalone DEX files and/or APK/JAR containers as one logical project. */
        fun open(sources: List<File>, cacheDir: File): DexWorkspace {
            require(sources.isNotEmpty()) { "Nenhum DEX selecionado" }
            val temps = ArrayList<File>()
            val parsed = ArrayList<DexSummary>()
            var totalBytes = 0L
            try {
                for (source in sources.distinctBy { it.canonicalPath }) {
                    require(source.isFile) { "Arquivo não encontrado: ${source.name}" }
                    if (source.extension.equals("dex", true)) {
                        require(source.length() in 1..MAX_DEX_BYTES) { "DEX grande demais: ${source.name}" }
                        totalBytes += source.length()
                        require(totalBytes <= MAX_TOTAL_BYTES) { "Conjunto DEX grande demais" }
                        parsed += parseDex(source, source.name)
                    } else {
                        ZipFile(source).use { zip ->
                            val entries = zip.entries().asSequence()
                                .filter { Regex("(?:.*/)?classes(\\d*)\\.dex").matches(it.name) }
                                .toList()
                            require(entries.isNotEmpty()) { "Nenhum DEX encontrado em ${source.name}" }
                            for (entry in entries) {
                                require(entry.size in 0..MAX_DEX_BYTES) { "DEX grande demais" }
                                totalBytes += entry.size.coerceAtLeast(0)
                                require(totalBytes <= MAX_TOTAL_BYTES) { "Conjunto DEX grande demais" }
                                val temp = File.createTempFile("dex-", ".dex", cacheDir).also(temps::add)
                                zip.getInputStream(entry).use { input ->
                                    FileOutputStream(temp).use { output -> input.copyTo(output, 128 * 1024) }
                                }
                                parsed += parseDex(temp, "${source.name}!${entry.name.substringAfterLast('/')}")
                            }
                        }
                    }
                }
                require(parsed.isNotEmpty()) { "Nenhum DEX válido encontrado" }
                return DexWorkspace(parsed, temps)
            } catch (error: Throwable) {
                temps.forEach { it.delete() }
                throw error
            }
        }

        private fun parseDex(file: File, name: String): DexSummary = RandomAccessFile(file, "r").use { input ->
            val magic = ByteArray(8).also(input::readFully)
            require(magic.copyOfRange(0, 4).contentEquals(byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte()))) { "Magic DEX inválido" }
            fun uintAt(offset: Long): Long { input.seek(offset); return readUIntLE(input) }
            val stringsSize = checkedCount(uintAt(56), "strings")
            val stringsOff = uintAt(60)
            val typesSize = checkedCount(uintAt(64), "types")
            val typesOff = uintAt(68)
            val fieldsSize = checkedCount(uintAt(80), "fields")
            val fieldsOff = uintAt(84)
            val methodsSize = checkedCount(uintAt(88), "methods")
            val methodsOff = uintAt(92)
            val classesSize = checkedCount(uintAt(96), "classes")
            val classesOff = uintAt(100)

            val strings = ArrayList<String>(stringsSize)
            for (i in 0 until stringsSize) {
                input.seek(stringsOff + i * 4L)
                val dataOff = readUIntLE(input)
                require(dataOff in 0 until input.length()) { "Offset de string inválido" }
                input.seek(dataOff)
                readUleb128(input)
                val bytes = ArrayList<Byte>()
                while (bytes.size < MAX_STRING_BYTES) {
                    val b = input.read()
                    if (b <= 0) break
                    bytes += b.toByte()
                }
                strings += bytes.toByteArray().toString(Charset.forName("UTF-8"))
            }

            val types = ArrayList<String>(typesSize)
            for (i in 0 until typesSize) {
                input.seek(typesOff + i * 4L)
                types += strings.getOrElse(readUIntLE(input).toInt()) { "<invalid>" }
            }

            val fields = ArrayList<String>(fieldsSize)
            for (i in 0 until fieldsSize) {
                input.seek(fieldsOff + i * 8L)
                val classIndex = readUShortLE(input)
                val typeIndex = readUShortLE(input)
                val nameIndex = readUIntLE(input).toInt()
                fields += "${types.getOrElse(classIndex) { "?" }}->${strings.getOrElse(nameIndex) { "?" }}:${types.getOrElse(typeIndex) { "?" }}"
            }

            val methods = ArrayList<String>(methodsSize)
            for (i in 0 until methodsSize) {
                input.seek(methodsOff + i * 8L)
                val classIndex = readUShortLE(input)
                readUShortLE(input)
                val nameIndex = readUIntLE(input).toInt()
                methods += "${types.getOrElse(classIndex) { "?" }}->${strings.getOrElse(nameIndex) { "?" }}"
            }

            val classes = ArrayList<String>(classesSize)
            val classInfo = LinkedHashMap<String, DexClassInfo>(classesSize)
            for (i in 0 until classesSize) {
                input.seek(classesOff + i * 32L)
                val classIndex = readUIntLE(input).toInt()
                val accessFlags = readUIntLE(input)
                val superIndexRaw = readUIntLE(input)
                val interfacesOff = readUIntLE(input)
                val descriptor = types.getOrElse(classIndex) { "<invalid>" }
                val superDescriptor = if (superIndexRaw == NO_INDEX) null else types.getOrNull(superIndexRaw.toInt())
                val interfaces = readTypeList(input, interfacesOff, types)
                classes += descriptor
                classInfo[descriptor] = DexClassInfo(descriptor, superDescriptor, interfaces, accessFlags)
            }
            DexSummary(name, strings, types, classes, methods, fields, classInfo)
        }

        private fun readTypeList(input: RandomAccessFile, offset: Long, types: List<String>): List<String> {
            if (offset == 0L) return emptyList()
            require(offset in 0 until input.length()) { "Offset de interfaces inválido" }
            val resume = input.filePointer
            return try {
                input.seek(offset)
                val count = checkedCount(readUIntLE(input), "interfaces")
                require(count <= 65_535) { "Lista de interfaces inválida" }
                List(count) { types.getOrElse(readUShortLE(input)) { "<invalid>" } }
            } finally {
                input.seek(resume)
            }
        }

        private fun checkedCount(value: Long, label: String): Int {
            require(value in 0..5_000_000L) { "Quantidade de $label inválida" }
            return value.toInt()
        }

        private fun readUIntLE(input: RandomAccessFile): Long = (input.readUnsignedByte().toLong() or
            (input.readUnsignedByte().toLong() shl 8) or
            (input.readUnsignedByte().toLong() shl 16) or
            (input.readUnsignedByte().toLong() shl 24)) and 0xffffffffL

        private fun readUShortLE(input: RandomAccessFile): Int = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)

        private fun readUleb128(input: RandomAccessFile): Int {
            var result = 0
            var shift = 0
            repeat(5) {
                val b = input.readUnsignedByte()
                result = result or ((b and 0x7f) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            throw EOFException("ULEB128 inválido")
        }

        private fun <T> java.util.Enumeration<T>.asSequence(): Sequence<T> = sequence {
            while (hasMoreElements()) yield(nextElement())
        }

        private const val NO_INDEX = 0xffffffffL
        private const val MAX_STRING_BYTES = 4 * 1024 * 1024
        private const val MAX_DEX_BYTES = 128L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    }
}
