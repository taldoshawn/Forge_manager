package com.forgemanager.app.features.resources

import java.lang.Float.intBitsToFloat

/**
 * Read-only decoder for Android's binary XML (AXML). It is intentionally small
 * and dependency-free so AndroidManifest.xml can be inspected as normal XML
 * without pretending the binary file is UTF-8 text.
 */
object AndroidBinaryXmlDecoder {
    fun decode(data: ByteArray): String {
        require(data.size >= 8 && u16(data, 0) == RES_XML_TYPE) { "Não é Android Binary XML" }
        val rootSize = u32(data, 4)
        require(rootSize in 8..data.size) { "AXML truncado" }

        var offset = u16(data, 2)
        var strings: List<String> = emptyList()
        while (offset + 8 <= rootSize) {
            val type = u16(data, offset)
            val size = u32(data, offset + 4)
            require(size >= 8 && offset + size <= rootSize) { "Chunk AXML inválido" }
            if (type == RES_STRING_POOL_TYPE) {
                strings = parseStringPool(data, offset)
                break
            }
            offset += size
        }
        require(strings.isNotEmpty()) { "String pool AXML não encontrado" }

        val out = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        val namespaces = LinkedHashMap<Int, String>()
        val pendingNamespaces = ArrayList<Pair<String, String>>()
        var depth = 0
        offset = u16(data, 2)

        while (offset + 8 <= rootSize) {
            val type = u16(data, offset)
            val size = u32(data, offset + 4)
            require(size >= 8 && offset + size <= rootSize) { "Chunk AXML inválido em 0x${offset.toString(16)}" }
            when (type) {
                RES_XML_START_NAMESPACE_TYPE -> if (size >= 24) {
                    val prefixIndex = u32(data, offset + 16)
                    val uriIndex = u32(data, offset + 20)
                    val prefix = stringAt(strings, prefixIndex).orEmpty()
                    val uri = stringAt(strings, uriIndex).orEmpty()
                    if (uri.isNotBlank()) {
                        namespaces[uriIndex] = prefix
                        pendingNamespaces += prefix to uri
                    }
                }
                RES_XML_END_NAMESPACE_TYPE -> if (size >= 24) {
                    namespaces.remove(u32(data, offset + 20))
                }
                RES_XML_START_ELEMENT_TYPE -> if (size >= 36) {
                    val nsIndex = u32(data, offset + 16)
                    val nameIndex = u32(data, offset + 20)
                    val name = qualifiedName(strings, namespaces, nsIndex, nameIndex)
                    indent(out, depth).append('<').append(name)
                    if (pendingNamespaces.isNotEmpty()) {
                        pendingNamespaces.distinct().forEach { (prefix, uri) ->
                            out.append(' ').append("xmlns")
                            if (prefix.isNotBlank()) out.append(':').append(prefix)
                            out.append("=\"").append(escape(uri)).append('"')
                        }
                        pendingNamespaces.clear()
                    }
                    val attributeStart = u16(data, offset + 24)
                    val attributeSize = u16(data, offset + 26).coerceAtLeast(20)
                    val attributeCount = u16(data, offset + 28)
                    var attrOffset = offset + 16 + attributeStart
                    repeat(attributeCount) {
                        if (attrOffset + 20 > offset + size) return@repeat
                        val attrNs = u32(data, attrOffset)
                        val attrName = u32(data, attrOffset + 4)
                        val rawValue = u32(data, attrOffset + 8)
                        val dataType = data[attrOffset + 15].toInt() and 0xff
                        val typedData = u32(data, attrOffset + 16)
                        val key = qualifiedName(strings, namespaces, attrNs, attrName)
                        val value = stringAt(strings, rawValue)
                            ?: typedValue(strings, dataType, typedData)
                        out.append('\n')
                        indent(out, depth + 1).append(key).append("=\"").append(escape(value)).append('"')
                        attrOffset += attributeSize
                    }
                    out.append(">\n")
                    depth++
                }
                RES_XML_END_ELEMENT_TYPE -> if (size >= 24) {
                    depth = (depth - 1).coerceAtLeast(0)
                    val name = qualifiedName(strings, namespaces, u32(data, offset + 16), u32(data, offset + 20))
                    indent(out, depth).append("</").append(name).append(">\n")
                }
                RES_XML_CDATA_TYPE -> if (size >= 28) {
                    val text = stringAt(strings, u32(data, offset + 16)).orEmpty()
                    if (text.isNotEmpty()) indent(out, depth).append(escape(text)).append('\n')
                }
            }
            offset += size
        }
        return out.toString()
    }

    private fun parseStringPool(data: ByteArray, chunk: Int): List<String> {
        val headerSize = u16(data, chunk + 2)
        val chunkSize = u32(data, chunk + 4)
        val count = u32(data, chunk + 8)
        val flags = u32(data, chunk + 16)
        val stringsStart = u32(data, chunk + 20)
        require(headerSize >= 28 && count in 0..2_000_000 && chunk + chunkSize <= data.size) { "String pool inválido" }
        val utf8 = flags and UTF8_FLAG != 0
        return List(count) { index ->
            val entry = chunk + headerSize + index * 4
            require(entry + 4 <= chunk + chunkSize) { "Índice de string inválido" }
            val stringOffset = chunk + stringsStart + u32(data, entry)
            require(stringOffset in chunk until chunk + chunkSize) { "Offset de string inválido" }
            if (utf8) readUtf8(data, stringOffset, chunk + chunkSize) else readUtf16(data, stringOffset, chunk + chunkSize)
        }
    }

    private fun readUtf8(data: ByteArray, start: Int, end: Int): String {
        var p = start
        val (_, p1) = readLength8(data, p, end); p = p1
        val (byteLength, p2) = readLength8(data, p, end); p = p2
        require(byteLength >= 0 && p + byteLength <= end) { "String UTF-8 truncada" }
        return data.copyOfRange(p, p + byteLength).toString(Charsets.UTF_8)
    }

    private fun readLength8(data: ByteArray, start: Int, end: Int): Pair<Int, Int> {
        require(start < end)
        val first = data[start].toInt() and 0xff
        return if (first and 0x80 == 0) first to (start + 1)
        else {
            require(start + 1 < end)
            (((first and 0x7f) shl 8) or (data[start + 1].toInt() and 0xff)) to (start + 2)
        }
    }

    private fun readUtf16(data: ByteArray, start: Int, end: Int): String {
        var p = start
        require(p + 2 <= end)
        var length = u16(data, p); p += 2
        if (length and 0x8000 != 0) {
            require(p + 2 <= end)
            length = ((length and 0x7fff) shl 16) or u16(data, p)
            p += 2
        }
        val bytes = length * 2
        require(length >= 0 && p + bytes <= end) { "String UTF-16 truncada" }
        return data.copyOfRange(p, p + bytes).toString(Charsets.UTF_16LE)
    }

    private fun typedValue(strings: List<String>, type: Int, value: Int): String = when (type) {
        TYPE_NULL -> ""
        TYPE_REFERENCE -> "@0x%08x".format(value)
        TYPE_ATTRIBUTE -> "?0x%08x".format(value)
        TYPE_STRING -> stringAt(strings, value).orEmpty()
        TYPE_FLOAT -> intBitsToFloat(value).toString()
        TYPE_INT_DEC -> value.toString()
        TYPE_INT_HEX -> "0x%08x".format(value)
        TYPE_INT_BOOLEAN -> if (value != 0) "true" else "false"
        in TYPE_FIRST_COLOR_INT..TYPE_LAST_COLOR_INT -> "#%08x".format(value)
        else -> "0x%08x".format(value)
    }

    private fun qualifiedName(strings: List<String>, namespaces: Map<Int, String>, nsIndex: Int, nameIndex: Int): String {
        val raw = stringAt(strings, nameIndex).orEmpty().ifBlank { "unknown" }
        val prefix = namespaces[nsIndex].orEmpty()
        return if (prefix.isBlank()) raw else "$prefix:$raw"
    }

    private fun stringAt(strings: List<String>, index: Int): String? =
        if (index == NO_INDEX || index !in strings.indices) null else strings[index]

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun indent(out: StringBuilder, depth: Int): StringBuilder {
        repeat(depth.coerceIn(0, 200)) { out.append("    ") }
        return out
    }

    private fun u16(data: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 1 < data.size)
        return (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun u32(data: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 3 < data.size)
        return (data[offset].toInt() and 0xff) or
            ((data[offset + 1].toInt() and 0xff) shl 8) or
            ((data[offset + 2].toInt() and 0xff) shl 16) or
            ((data[offset + 3].toInt() and 0xff) shl 24)
    }

    private const val NO_INDEX = -1
    private const val UTF8_FLAG = 0x100
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_TYPE = 0x0003
    private const val RES_XML_START_NAMESPACE_TYPE = 0x0100
    private const val RES_XML_END_NAMESPACE_TYPE = 0x0101
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_END_ELEMENT_TYPE = 0x0103
    private const val RES_XML_CDATA_TYPE = 0x0104
    private const val TYPE_NULL = 0x00
    private const val TYPE_REFERENCE = 0x01
    private const val TYPE_ATTRIBUTE = 0x02
    private const val TYPE_STRING = 0x03
    private const val TYPE_FLOAT = 0x04
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val TYPE_INT_BOOLEAN = 0x12
    private const val TYPE_FIRST_COLOR_INT = 0x1c
    private const val TYPE_LAST_COLOR_INT = 0x1f
}
