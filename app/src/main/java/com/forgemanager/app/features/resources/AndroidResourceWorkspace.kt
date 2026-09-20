package com.forgemanager.app.features.resources

import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.xml.XMLFactory
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

class AndroidResourceWorkspace private constructor(
    val apk: File,
    val directory: File,
    val tableFile: File,
    val table: TableBlock
) : Closeable {

    fun decodeBinaryXml(entryName: String): String {
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry(entryName) ?: error("Entrada não encontrada: $entryName")
            require(entry.size in 0..MAX_XML_BYTES) { "AXML grande demais" }
            zip.getInputStream(entry).use { input ->
                val document: ResXmlDocument = if (entryName == "AndroidManifest.xml") {
                    AndroidManifestBlock.load(input)
                } else {
                    ResXmlDocument().also { doc ->
                        doc.setPackageBlock(table.pickOne())
                        doc.readBytes(input)
                    }
                }
                document.setPackageBlock(table.pickOne())
                return document.serializeToXml()
            }
        }
    }

    fun encodeBinaryXml(entryName: String, xml: String, destination: File) {
        require(xml.length <= MAX_XML_CHARS) { "XML grande demais" }
        val document: ResXmlDocument = if (entryName == "AndroidManifest.xml") AndroidManifestBlock() else ResXmlDocument()
        document.setPackageBlock(table.pickOne())
        document.parse(XMLFactory.newPullParser(xml))
        destination.parentFile?.mkdirs()
        document.writeBytes(destination)
    }

    fun saveTable(destination: File) {
        destination.parentFile?.mkdirs()
        table.refresh()
        table.writeBytes(destination)
    }

    override fun close() {
        runCatching { directory.deleteRecursively() }
    }

    companion object {
        fun open(apk: File, cacheDir: File): AndroidResourceWorkspace {
            require(apk.isFile) { "APK não encontrado" }
            val dir = File(cacheDir, "resources-${apk.nameWithoutExtension}-${System.nanoTime()}")
            require(dir.mkdirs()) { "Falha ao criar workspace de recursos" }
            try {
                val tableFile = File(dir, "resources.arsc")
                ZipFile(apk).use { zip ->
                    val entry = zip.getEntry("resources.arsc") ?: error("resources.arsc não encontrado")
                    require(entry.size in 0..MAX_ARSC_BYTES) { "resources.arsc grande demais" }
                    zip.getInputStream(entry).use { input -> tableFile.outputStream().buffered().use { input.copyTo(it, 128 * 1024) } }
                }
                val table = TableBlock.load(tableFile)
                require(table.pickOne() != null) { "Nenhum pacote de recursos encontrado" }
                return AndroidResourceWorkspace(apk, dir, tableFile, table)
            } catch (error: Throwable) {
                dir.deleteRecursively()
                throw error
            }
        }

        private const val MAX_ARSC_BYTES = 128L * 1024 * 1024
        private const val MAX_XML_BYTES = 32L * 1024 * 1024
        private const val MAX_XML_CHARS = 16 * 1024 * 1024
    }
}
