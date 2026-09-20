package com.forgemanager.app.features.apk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ApkZipAlignerTest {
    @Test fun storedEntryDataIsFourByteAlignedAndContentPreserved() {
        val dir = kotlin.io.path.createTempDirectory("forge-align-test").toFile()
        try {
            val input = File(dir, "input.apk")
            val output = File(dir, "output.apk")
            val payload = ByteArray(257) { (it * 17).toByte() }
            val crc = CRC32().apply { update(payload) }
            ZipOutputStream(input.outputStream()).use { zip ->
                val entry = ZipEntry("lib/arm64-v8a/libforge.so").apply {
                    method = ZipEntry.STORED
                    size = payload.size.toLong()
                    compressedSize = payload.size.toLong()
                    this.crc = crc.value
                }
                zip.putNextEntry(entry)
                zip.write(payload)
                zip.closeEntry()
            }

            ApkZipAligner.align(input, output)

            assertTrue(output.isFile)
            ZipFile(output).use { zip ->
                val entry = zip.getEntry("lib/arm64-v8a/libforge.so")
                assertEquals(ZipEntry.STORED, entry.method)
                assertArrayEquals(payload, zip.getInputStream(entry).readBytes())
            }

            val bytes = output.readBytes()
            assertEquals(0x50, bytes[0].toInt() and 0xFF)
            assertEquals(0x4B, bytes[1].toInt() and 0xFF)
            assertEquals(0x03, bytes[2].toInt() and 0xFF)
            assertEquals(0x04, bytes[3].toInt() and 0xFF)
            val nameLength = u16(bytes, 26)
            val extraLength = u16(bytes, 28)
            val dataOffset = 30 + nameLength + extraLength
            assertEquals(0, dataOffset % 4)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}
