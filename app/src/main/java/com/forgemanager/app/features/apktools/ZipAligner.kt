package com.forgemanager.app.features.apktools

import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Pure-Java APK ZIP aligner. Run before APK signing. */
object ZipAligner {
    data class Result(val entries: Int, val alignedStoredEntries: Int, val outputBytes: Long)

    fun align(input: File, output: File, alignment: Int = 4): Result {
        require(input.isFile) { "APK/ZIP de entrada não existe" }
        require(alignment in 4..65536 && alignment and (alignment - 1) == 0) { "Alinhamento inválido" }
        output.parentFile?.mkdirs()
        val temporary = File(output.parentFile, ".${output.name}.${System.nanoTime()}.tmp")
        var total = 0
        var aligned = 0
        try {
            ZipFile(input).use { source ->
                CountingOutputStream(FileOutputStream(temporary)).use { counting ->
                    ZipOutputStream(counting).use { zipOut ->
                        zipOut.setLevel(6)
                        val entries = source.entries()
                        while (entries.hasMoreElements()) {
                            val original = entries.nextElement()
                            val next = cloneEntry(original)
                            if (!original.isDirectory && original.method == ZipEntry.STORED) {
                                next.method = ZipEntry.STORED
                                next.size = original.size
                                next.compressedSize = original.size
                                next.crc = original.crc
                                next.extra = paddedExtra(original.extra ?: ByteArray(0), counting.count, original.name, alignment)
                                aligned++
                            } else if (!original.isDirectory) {
                                next.method = ZipEntry.DEFLATED
                                next.size = original.size
                                next.crc = original.crc
                            }
                            zipOut.putNextEntry(next)
                            if (!original.isDirectory) source.getInputStream(original).use { it.copyTo(zipOut, 128 * 1024) }
                            zipOut.closeEntry(); total++
                        }
                    }
                }
            }
            FileOutputStream(temporary, true).use { it.fd.sync() }
            if (output.exists() && !output.delete()) error("Não foi possível substituir ${output.name}")
            if (!temporary.renameTo(output)) { temporary.copyTo(output, overwrite = true); temporary.delete() }
            return Result(total, aligned, output.length())
        } catch (error: Throwable) { temporary.delete(); throw error }
    }

    private fun cloneEntry(source: ZipEntry): ZipEntry = ZipEntry(source.name).apply {
        time = source.time; comment = source.comment
        if (source.isDirectory) { method = ZipEntry.STORED; size = 0; compressedSize = 0; crc = 0 } else method = source.method
        extra = source.extra
    }

    private fun paddedExtra(baseExtra: ByteArray, outputPosition: Long, name: String, alignment: Int): ByteArray {
        val baseDataOffset = outputPosition + LOCAL_HEADER_FIXED + name.toByteArray(StandardCharsets.UTF_8).size + baseExtra.size
        val needed = ((alignment - (baseDataOffset % alignment)) % alignment).toInt()
        if (needed == 0) return baseExtra
        var totalPadding = needed
        while (totalPadding < 4) totalPadding += alignment
        require(baseExtra.size + totalPadding <= 0xffff) { "Extra field excederia o limite ZIP" }
        val result = ByteArray(baseExtra.size + totalPadding)
        baseExtra.copyInto(result)
        val at = baseExtra.size
        result[at] = 0x35; result[at + 1] = 0xF9.toByte()
        val payload = totalPadding - 4
        result[at + 2] = (payload and 0xff).toByte(); result[at + 3] = ((payload ushr 8) and 0xff).toByte()
        return result
    }

    private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        var count: Long = 0; private set
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }
    private const val LOCAL_HEADER_FIXED = 30L
}
