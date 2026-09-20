package com.forgemanager.app.features.resources

/** Lightweight structural validator/inspector for Android binary XML and resources.arsc. */
object AndroidBinaryResources {
    enum class Kind { BINARY_XML, RESOURCE_TABLE }

    data class Chunk(
        val offset: Int,
        val type: Int,
        val headerSize: Int,
        val size: Int,
        val depth: Int,
        val label: String
    )

    data class Report(val kind: Kind, val chunks: List<Chunk>)

    fun inspect(data: ByteArray, maxChunks: Int = 20_000): Report {
        require(data.size >= 8) { "Recurso binário truncado" }
        val rootType = u16(data, 0)
        val kind = when (rootType) {
            RES_XML_TYPE -> Kind.BINARY_XML
            RES_TABLE_TYPE -> Kind.RESOURCE_TABLE
            else -> error("Arquivo não é AXML nem resources.arsc")
        }
        val rootHeader = u16(data, 2)
        val rootSize = u32(data, 4)
        require(rootHeader >= 8 && rootSize >= rootHeader && rootSize <= data.size) { "Cabeçalho Android binário inválido" }

        val out = ArrayList<Chunk>()
        out += Chunk(0, rootType, rootHeader, rootSize, 0, label(rootType))
        walkChildren(data, rootHeader, rootSize, 1, out, maxChunks)
        return Report(kind, out)
    }

    fun isBinaryXml(data: ByteArray): Boolean =
        data.size >= 8 && u16(data, 0) == RES_XML_TYPE && validRoot(data)

    fun isResourceTable(data: ByteArray): Boolean =
        data.size >= 8 && u16(data, 0) == RES_TABLE_TYPE && validRoot(data)

    fun isPlainTextXml(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        val text = runCatching {
            val start = when {
                data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte() -> 3
                else -> 0
            }
            data.copyOfRange(start, minOf(data.size, 512)).toString(Charsets.UTF_8)
        }.getOrNull() ?: return false
        return text.trimStart().startsWith("<")
    }

    fun describe(report: Report): String = buildString {
        append(if (report.kind == Kind.BINARY_XML) "Android Binary XML" else "resources.arsc")
        append("\n\n")
        report.chunks.forEach { c ->
            repeat(c.depth) { append("  ") }
            append(String.format("0x%08X", c.offset))
            append("  ").append(c.label)
            append("  •  ").append(c.size).append(" B")
            append('\n')
        }
    }

    private fun walkChildren(
        data: ByteArray,
        start: Int,
        end: Int,
        depth: Int,
        out: MutableList<Chunk>,
        maxChunks: Int
    ) {
        if (depth > MAX_DEPTH) return
        var offset = start
        while (offset + 8 <= end && out.size < maxChunks) {
            val type = u16(data, offset)
            val header = u16(data, offset + 2)
            val size = u32(data, offset + 4)
            require(header >= 8 && size >= header) { "Chunk Android inválido em 0x${offset.toString(16)}" }
            val chunkEnd = offset.toLong() + size.toLong()
            require(chunkEnd <= end.toLong() && chunkEnd <= data.size.toLong()) { "Chunk Android fora dos limites" }
            out += Chunk(offset, type, header, size, depth, label(type))
            if (type == RES_TABLE_PACKAGE_TYPE) {
                walkChildren(data, offset + header, chunkEnd.toInt(), depth + 1, out, maxChunks)
            }
            offset = chunkEnd.toInt()
        }
        require(offset == end || end - offset < 8) { "Estrutura Android binária desalinhada" }
    }

    private fun validRoot(data: ByteArray): Boolean = runCatching {
        val header = u16(data, 2)
        val size = u32(data, 4)
        header >= 8 && size >= header && size <= data.size
    }.getOrDefault(false)

    private fun label(type: Int): String = when (type) {
        RES_NULL_TYPE -> "NULL"
        RES_STRING_POOL_TYPE -> "String pool"
        RES_TABLE_TYPE -> "Resource table"
        RES_XML_TYPE -> "Binary XML"
        RES_XML_START_NAMESPACE_TYPE -> "XML namespace start"
        RES_XML_END_NAMESPACE_TYPE -> "XML namespace end"
        RES_XML_START_ELEMENT_TYPE -> "XML element start"
        RES_XML_END_ELEMENT_TYPE -> "XML element end"
        RES_XML_CDATA_TYPE -> "XML CDATA"
        RES_XML_RESOURCE_MAP_TYPE -> "XML resource map"
        RES_TABLE_PACKAGE_TYPE -> "Resource package"
        RES_TABLE_TYPE_TYPE -> "Resource type"
        RES_TABLE_TYPE_SPEC_TYPE -> "Resource type spec"
        RES_TABLE_LIBRARY_TYPE -> "Resource library"
        RES_TABLE_OVERLAYABLE_TYPE -> "Overlayable"
        RES_TABLE_OVERLAYABLE_POLICY_TYPE -> "Overlayable policy"
        RES_TABLE_STAGED_ALIAS_TYPE -> "Staged alias"
        else -> "Chunk 0x${type.toString(16).uppercase()}"
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

    private const val MAX_DEPTH = 4
    private const val RES_NULL_TYPE = 0x0000
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_TABLE_TYPE = 0x0002
    private const val RES_XML_TYPE = 0x0003
    private const val RES_XML_START_NAMESPACE_TYPE = 0x0100
    private const val RES_XML_END_NAMESPACE_TYPE = 0x0101
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_END_ELEMENT_TYPE = 0x0103
    private const val RES_XML_CDATA_TYPE = 0x0104
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
    private const val RES_TABLE_PACKAGE_TYPE = 0x0200
    private const val RES_TABLE_TYPE_TYPE = 0x0201
    private const val RES_TABLE_TYPE_SPEC_TYPE = 0x0202
    private const val RES_TABLE_LIBRARY_TYPE = 0x0203
    private const val RES_TABLE_OVERLAYABLE_TYPE = 0x0204
    private const val RES_TABLE_OVERLAYABLE_POLICY_TYPE = 0x0205
    private const val RES_TABLE_STAGED_ALIAS_TYPE = 0x0206
}
