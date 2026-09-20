package com.forgemanager.app.features.resources

import java.nio.charset.StandardCharsets

/**
 * Conservative Android binary string-pool reader/patcher for AXML/resources.arsc.
 *
 * This editor intentionally performs in-place replacements only. It never grows a string pool,
 * moves chunks or rewrites resource offsets, which keeps edits predictable and avoids producing
 * silently corrupted binary XML/resources.arsc files.
 */
object BinaryStringPools {
    data class Entry(
        val poolOffset: Int,
        val index: Int,
        val value: String,
        val utf8: Boolean,
        val firstLengthOffset: Int,
        val firstLengthBytes: Int,
        val secondLengthOffset: Int,
        val secondLengthBytes: Int,
        val dataOffset: Int,
        val encodedCapacity: Int,
        val utf16Capacity: Int
    ) {
        val key: String get() = "$poolOffset:$index"
    }

    fun find(data: ByteArray, maxStrings: Int = 100_000): List<Entry> {
        if (data.size < STRING_POOL_HEADER_SIZE || maxStrings <= 0) return emptyList()

        val result = ArrayList<Entry>()
        var offset = 0
        while (offset + STRING_POOL_HEADER_SIZE <= data.size && result.size < maxStrings) {
            if (u16(data, offset) == RES_STRING_POOL_TYPE) {
                parsePool(data, offset, result, maxStrings)
            }
            offset += 4
        }
        return result.distinctBy { it.key }
    }

    fun replace(data: ByteArray, entry: Entry, replacement: String) {
        require(entry.dataOffset in data.indices) { "String pool inválido" }
        if (entry.utf8) {
            replaceUtf8(data, entry, replacement)
        } else {
            replaceUtf16(data, entry, replacement)
        }
    }

    private fun parsePool(
        data: ByteArray,
        base: Int,
        output: MutableList<Entry>,
        maxStrings: Int
    ) {
        val headerSize = u16(data, base + 2)
        val chunkSize = u32(data, base + 4)
        if (headerSize < STRING_POOL_HEADER_SIZE || chunkSize < headerSize) return

        val chunkEnd = base.toLong() + chunkSize.toLong()
        if (chunkEnd > data.size.toLong()) return

        val stringCount = u32(data, base + 8)
        val styleCount = u32(data, base + 12)
        val flags = u32(data, base + 16)
        val stringsStart = u32(data, base + 20)
        val stylesStart = u32(data, base + 24)

        if (stringCount < 0 || stringCount > MAX_POOL_STRINGS) return
        if (styleCount < 0 || stringsStart < 0 || stylesStart < 0) return

        val offsetsStart = base.toLong() + headerSize.toLong()
        val offsetBytes = stringCount.toLong() * 4L + styleCount.toLong() * 4L
        if (offsetsStart + offsetBytes > chunkEnd) return
        if (stringsStart < headerSize || stringsStart >= chunkSize) return
        if (stylesStart != 0 && (stylesStart < stringsStart || stylesStart > chunkSize)) return

        val utf8 = (flags and UTF8_FLAG) != 0
        for (index in 0 until stringCount) {
            if (output.size >= maxStrings) return

            val offsetPosition = base + headerSize + index * 4
            val relativeOffset = u32(data, offsetPosition)
            if (relativeOffset < 0) continue

            val startLong = base.toLong() + stringsStart.toLong() + relativeOffset.toLong()
            if (startLong < base.toLong() || startLong >= chunkEnd || startLong > Int.MAX_VALUE) {
                continue
            }

            val start = startLong.toInt()
            val entry = runCatching {
                if (utf8) {
                    parseUtf8(data, base, chunkSize, index, start)
                } else {
                    parseUtf16(data, base, chunkSize, index, start)
                }
            }.getOrNull()

            if (entry != null) output.add(entry)
        }
    }

    private fun parseUtf8(
        data: ByteArray,
        poolOffset: Int,
        chunkSize: Int,
        index: Int,
        start: Int
    ): Entry? {
        val utf16Length = readLength8(data, start) ?: return null
        val byteLengthOffset = start + utf16Length.bytes
        val byteLength = readLength8(data, byteLengthOffset) ?: return null
        val dataOffset = byteLengthOffset + byteLength.bytes
        val end = dataOffset.toLong() + byteLength.value.toLong()
        val poolEnd = poolOffset.toLong() + chunkSize.toLong()

        // A valid UTF-8 string has a trailing NUL byte inside the pool.
        if (end >= poolEnd || end >= data.size.toLong()) return null
        if (data[end.toInt()].toInt() != 0) return null

        val value = data.copyOfRange(dataOffset, end.toInt())
            .toString(StandardCharsets.UTF_8)

        return Entry(
            poolOffset = poolOffset,
            index = index,
            value = value,
            utf8 = true,
            firstLengthOffset = start,
            firstLengthBytes = utf16Length.bytes,
            secondLengthOffset = byteLengthOffset,
            secondLengthBytes = byteLength.bytes,
            dataOffset = dataOffset,
            encodedCapacity = byteLength.value,
            utf16Capacity = utf16Length.value
        )
    }

    private fun parseUtf16(
        data: ByteArray,
        poolOffset: Int,
        chunkSize: Int,
        index: Int,
        start: Int
    ): Entry? {
        val length = readLength16(data, start) ?: return null
        val dataOffset = start + length.bytes
        val encodedBytes = length.value.toLong() * 2L
        val end = dataOffset.toLong() + encodedBytes
        val poolEnd = poolOffset.toLong() + chunkSize.toLong()

        // UTF-16 strings end with a two-byte NUL terminator.
        if (end + 2L > poolEnd || end + 2L > data.size.toLong()) return null
        if (data[end.toInt()].toInt() != 0 || data[end.toInt() + 1].toInt() != 0) return null

        val value = data.copyOfRange(dataOffset, end.toInt())
            .toString(StandardCharsets.UTF_16LE)

        return Entry(
            poolOffset = poolOffset,
            index = index,
            value = value,
            utf8 = false,
            firstLengthOffset = start,
            firstLengthBytes = length.bytes,
            secondLengthOffset = -1,
            secondLengthBytes = 0,
            dataOffset = dataOffset,
            encodedCapacity = length.value * 2,
            utf16Capacity = length.value
        )
    }

    private fun replaceUtf8(data: ByteArray, entry: Entry, replacement: String) {
        val encoded = replacement.toByteArray(StandardCharsets.UTF_8)
        val utf16Length = replacement.length

        require(encoded.size <= entry.encodedCapacity) {
            "Substituição UTF-8 excede ${entry.encodedCapacity} bytes"
        }
        require(utf16Length <= entry.utf16Capacity) {
            "Substituição excede ${entry.utf16Capacity} unidades UTF-16"
        }

        writeLength8(
            data,
            entry.firstLengthOffset,
            entry.firstLengthBytes,
            utf16Length
        )
        writeLength8(
            data,
            entry.secondLengthOffset,
            entry.secondLengthBytes,
            encoded.size
        )

        encoded.copyInto(data, entry.dataOffset)
        val clearEnd = (entry.dataOffset + entry.encodedCapacity + 1).coerceAtMost(data.size)
        for (position in entry.dataOffset + encoded.size until clearEnd) {
            data[position] = 0
        }
    }

    private fun replaceUtf16(data: ByteArray, entry: Entry, replacement: String) {
        val encoded = replacement.toByteArray(StandardCharsets.UTF_16LE)
        val utf16Length = replacement.length

        require(utf16Length <= entry.utf16Capacity) {
            "Substituição excede ${entry.utf16Capacity} unidades UTF-16"
        }
        require(encoded.size <= entry.encodedCapacity) { "Substituição UTF-16 excede o espaço disponível" }

        writeLength16(
            data,
            entry.firstLengthOffset,
            entry.firstLengthBytes,
            utf16Length
        )
        encoded.copyInto(data, entry.dataOffset)

        val clearEnd = (entry.dataOffset + entry.encodedCapacity + 2).coerceAtMost(data.size)
        for (position in entry.dataOffset + encoded.size until clearEnd) {
            data[position] = 0
        }
    }

    private data class Length(val value: Int, val bytes: Int)

    private fun readLength8(data: ByteArray, offset: Int): Length? {
        if (offset !in data.indices) return null
        val first = data[offset].toInt() and 0xff
        return if ((first and 0x80) == 0) {
            Length(first, 1)
        } else {
            if (offset + 1 >= data.size) return null
            val second = data[offset + 1].toInt() and 0xff
            Length(((first and 0x7f) shl 8) or second, 2)
        }
    }

    private fun writeLength8(data: ByteArray, offset: Int, bytes: Int, value: Int) {
        require(offset >= 0 && offset + bytes <= data.size) { "Offset UTF-8 inválido" }
        when (bytes) {
            1 -> {
                require(value <= 0x7f) { "Comprimento não cabe no encoding original" }
                data[offset] = value.toByte()
            }
            2 -> {
                require(value <= 0x7fff) { "Comprimento não cabe no encoding original" }
                data[offset] = (0x80 or ((value ushr 8) and 0x7f)).toByte()
                data[offset + 1] = (value and 0xff).toByte()
            }
            else -> error("Comprimento UTF-8 inválido")
        }
    }

    private fun readLength16(data: ByteArray, offset: Int): Length? {
        if (offset < 0 || offset + 1 >= data.size) return null
        val first = u16(data, offset)
        if (first < 0) return null

        return if ((first and 0x8000) == 0) {
            Length(first, 2)
        } else {
            if (offset + 3 >= data.size) return null
            val second = u16(data, offset + 2)
            if (second < 0) return null
            Length(((first and 0x7fff) shl 16) or second, 4)
        }
    }

    private fun writeLength16(data: ByteArray, offset: Int, bytes: Int, value: Int) {
        require(offset >= 0 && offset + bytes <= data.size) { "Offset UTF-16 inválido" }
        when (bytes) {
            2 -> {
                require(value <= 0x7fff) { "Comprimento não cabe no encoding original" }
                put16(data, offset, value)
            }
            4 -> {
                require(value <= 0x7fffffff) { "Comprimento não cabe no encoding original" }
                put16(data, offset, 0x8000 or ((value ushr 16) and 0x7fff))
                put16(data, offset + 2, value and 0xffff)
            }
            else -> error("Comprimento UTF-16 inválido")
        }
    }

    private fun u16(data: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 1 >= data.size) return -1
        return (data[offset].toInt() and 0xff) or
            ((data[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun u32(data: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 3 >= data.size) return -1
        return (data[offset].toInt() and 0xff) or
            ((data[offset + 1].toInt() and 0xff) shl 8) or
            ((data[offset + 2].toInt() and 0xff) shl 16) or
            ((data[offset + 3].toInt() and 0xff) shl 24)
    }

    private fun put16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xff).toByte()
        data[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }

    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val UTF8_FLAG = 0x00000100
    private const val STRING_POOL_HEADER_SIZE = 28
    private const val MAX_POOL_STRINGS = 1_000_000
}
