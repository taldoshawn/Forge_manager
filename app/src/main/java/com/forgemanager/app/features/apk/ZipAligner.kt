package com.forgemanager.app.features.apk

import java.io.FilterOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ZipAligner {
    data class Result(val entries: Int, val alignedStoredEntries: Int, val outputBytes: Long)

    fun align(input: File, output: File, alignment: Int = 4): Result {
        require(input.isFile) { "APK/ZIP não encontrado" }
        require(alignment in 2..4096 && alignment and (alignment - 1) == 0) { "Alinhamento inválido" }
        require(input.canonicalFile != output.canonicalFile) { "Use um arquivo de saída diferente" }
        output.parentFile?.mkdirs()
        val temp = File(output.parentFile ?: input.parentFile ?: File("."), ".forge-align-${System.nanoTime()}.tmp")
        var entries = 0
        var aligned = 0
        try {
            FileOutputStream(temp).use { raw ->
                val counted = CountingOutputStream(raw)
                ZipOutputStream(counted).use { zipOut ->
                    ZipFile(input).use { zipIn ->
                        val enumeration = zipIn.entries()
                        while (enumeration.hasMoreElements()) {
                            val old = enumeration.nextElement()
                            val newEntry = ZipEntry(old.name).apply {
                                method = old.method
                                time = old.time
                                comment = old.comment
                                extra = old.extra?.takeIf { it.size <= MAX_EXTRA }
                                if (old.method == ZipEntry.STORED) {
                                    size = old.size
                                    compressedSize = old.size
                                    crc = old.crc
                                }
                            }
                            if (old.method == ZipEntry.STORED) {
                                val nameSize = old.name.toByteArray(StandardCharsets.UTF_8).size
                                val oldExtra = newEntry.extra ?: ByteArray(0)
                                val baseDataOffset = counted.count + LOCAL_HEADER_SIZE + nameSize + oldExtra.size
                                val paddingField = paddingExtra(baseDataOffset, alignment)
                                if (paddingField.isNotEmpty()) {
                                    require(oldExtra.size + paddingField.size <= MAX_EXTRA) { "Extra field excede o limite ZIP" }
                                    newEntry.extra = oldExtra + paddingField
                                }
                                aligned++
                            }
                            zipOut.putNextEntry(newEntry)
                            if (!old.isDirectory) {
                                zipIn.getInputStream(old).use { inputStream -> inputStream.copyTo(zipOut, 128 * 1024) }
                            }
                            zipOut.closeEntry()
                            entries++
                            require(entries <= MAX_ENTRIES) { "ZIP possui entradas demais" }
                        }
                    }
                }
            }
            if (output.exists() && !output.delete()) error("Não foi possível substituir a saída")
            if (!temp.renameTo(output)) {
                temp.copyTo(output, overwrite = true)
                temp.delete()
            }
            return Result(entries, aligned, output.length())
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    private fun paddingExtra(baseDataOffset: Long, alignment: Int): ByteArray {
        if (baseDataOffset % alignment == 0L) return ByteArray(0)
        val payload = ((alignment - ((baseDataOffset + 4) % alignment)) % alignment).toInt()
        return ByteArray(4 + payload).apply {
            // Private extra-field id 0xD935, little-endian. The payload is only alignment padding.
            this[0] = 0x35
            this[1] = 0xD9.toByte()
            this[2] = (payload and 0xff).toByte()
            this[3] = ((payload ushr 8) and 0xff).toByte()
        }
    }

    private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        var count: Long = 0
            private set
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len.toLong() }
    }

    private const val LOCAL_HEADER_SIZE = 30
    private const val MAX_EXTRA = 0xffff
    private const val MAX_ENTRIES = 500_000
}
