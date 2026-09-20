package com.forgemanager.app.features.apk

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Rewrites the ZIP and aligns data of STORED entries to four-byte boundaries.
 *
 * This is the part relevant to Android's classic zipalign rule. Because rewriting invalidates APK
 * signing blocks, callers must run this before APK signing. Deflated entries do not require 4-byte
 * data alignment. The original file is never modified in place.
 */
object ApkZipAligner {
    private const val ALIGNMENT = 4
    private const val ALIGN_EXTRA_ID = 0xD935

    fun align(input: File, output: File): File {
        require(input.isFile) { "APK/ZIP de entrada não encontrado" }
        require(input.canonicalPath != output.canonicalPath) { "Zipalign exige um arquivo de saída separado" }
        output.parentFile?.mkdirs()
        val temp = File(output.parentFile ?: input.parentFile, ".${output.name}.forge-align-${System.nanoTime()}.tmp")
        if (temp.exists()) temp.delete()
        try {
            ZipFile(input).use { zip ->
                val counted = CountingOutputStream(BufferedOutputStream(FileOutputStream(temp), 128 * 1024))
                ZipOutputStream(counted, StandardCharsets.UTF_8).use { out ->
                    out.setLevel(Deflater.DEFAULT_COMPRESSION)
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val source = entries.nextElement()
                        require(!source.name.startsWith('/') && !source.name.contains("../")) { "Entrada ZIP insegura: ${source.name}" }
                        val target = ZipEntry(source.name).apply {
                            method = source.method
                            comment = source.comment
                            time = source.time
                            if (source.method == ZipEntry.STORED) {
                                size = source.size
                                compressedSize = source.size
                                crc = source.crc
                            }
                        }
                        val cleanExtra = stripAlignmentExtra(source.extra)
                        if (source.method == ZipEntry.STORED) {
                            val nameBytes = source.name.toByteArray(StandardCharsets.UTF_8).size
                            val baseDataOffset = counted.count + 30L + nameBytes + cleanExtra.size
                            val mod = (baseDataOffset % ALIGNMENT).toInt()
                            target.extra = if (mod == 0) cleanExtra else cleanExtra + alignmentExtra((ALIGNMENT - mod) % ALIGNMENT)
                        } else target.extra = cleanExtra
                        out.putNextEntry(target)
                        if (!source.isDirectory) zip.getInputStream(source).use { it.copyTo(out, 128 * 1024) }
                        out.closeEntry()
                    }
                }
            }
            require(temp.isFile && temp.length() > 0) { "Zipalign produziu arquivo vazio" }
            if (output.exists() && !output.delete()) error("Não foi possível substituir ${output.name}")
            if (!temp.renameTo(output)) {
                temp.copyTo(output, overwrite = true)
                temp.delete()
            }
            return output
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    private fun alignmentExtra(payloadBytes: Int): ByteArray {
        val out = ByteArray(4 + payloadBytes)
        out[0] = (ALIGN_EXTRA_ID and 0xFF).toByte()
        out[1] = ((ALIGN_EXTRA_ID shr 8) and 0xFF).toByte()
        out[2] = (payloadBytes and 0xFF).toByte()
        out[3] = ((payloadBytes shr 8) and 0xFF).toByte()
        return out
    }

    private fun stripAlignmentExtra(extra: ByteArray?): ByteArray {
        if (extra == null || extra.size < 4) return extra ?: ByteArray(0)
        val result = ArrayList<Byte>(extra.size)
        var p = 0
        while (p + 4 <= extra.size) {
            val id = (extra[p].toInt() and 0xFF) or ((extra[p + 1].toInt() and 0xFF) shl 8)
            val size = (extra[p + 2].toInt() and 0xFF) or ((extra[p + 3].toInt() and 0xFF) shl 8)
            val end = p + 4 + size
            if (end > extra.size) break
            if (id != ALIGN_EXTRA_ID) for (i in p until end) result += extra[i]
            p = end
        }
        while (p < extra.size) result += extra[p++]
        return ByteArray(result.size) { result[it] }
    }

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count: Long = 0
            private set
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }
}
