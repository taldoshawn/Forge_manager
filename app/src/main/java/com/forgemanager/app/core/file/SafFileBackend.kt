package com.forgemanager.app.core.file

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.forgemanager.app.core.security.PathSecurity
import java.io.InputStream
import java.io.OutputStream

class SafFileBackend(context: Context) : FileBackend {
    override val id: String = "saf"
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override fun supports(location: FileLocation): Boolean = location is FileLocation.Saf

    private fun saf(location: FileLocation) = location as? FileLocation.Saf
        ?: throw FileAccessException("Local SAF inválido")

    override suspend fun capabilities(location: FileLocation): BackendCapabilities {
        val flags = queryLong(saf(location).uri(), DocumentsContract.Document.COLUMN_FLAGS)
        return BackendCapabilities(
            read = true,
            write = flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE.toLong() != 0L ||
                flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE.toLong() != 0L
        )
    }

    override suspend fun list(location: FileLocation, showHidden: Boolean): List<FileNode> {
        val parent = saf(location)
        val documentId = DocumentsContract.getDocumentId(parent.uri())
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(Uri.parse(parent.treeUri), documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val result = ArrayList<FileNode>()
        resolver.query(children, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val name = cursor.getString(1) ?: id.substringAfterLast('/')
                if (!showHidden && name.startsWith('.')) continue
                val mime = cursor.getString(2)
                val childUri = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(parent.treeUri), id)
                result += FileNode(
                    location = FileLocation.Saf(
                        childUri.toString(), parent.treeUri,
                        listOf(FileLocation.SafParent(parent.documentUri, parent.displayPath)) + parent.parents,
                        "${parent.displayPath.trimEnd('/')}/$name"
                    ),
                    name = name,
                    isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    size = if (cursor.isNull(3)) 0 else cursor.getLong(3),
                    modified = if (cursor.isNull(4)) 0 else cursor.getLong(4),
                    mimeType = mime
                )
            }
        } ?: throw FileAccessException("O provedor não permitiu listar a pasta")
        return result.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    override suspend fun stat(location: FileLocation): FileNode {
        val item = saf(location)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        resolver.query(item.uri(), projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val mime = cursor.getString(1)
                return FileNode(item, cursor.getString(0), mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    if (cursor.isNull(2)) 0 else cursor.getLong(2), if (cursor.isNull(3)) 0 else cursor.getLong(3), mime)
            }
        }
        throw FileAccessException("Documento não encontrado")
    }

    override suspend fun openInput(location: FileLocation): InputStream =
        resolver.openInputStream(saf(location).uri()) ?: throw FileAccessException("Não foi possível abrir para leitura")

    override suspend fun openOutput(location: FileLocation, truncate: Boolean): OutputStream =
        resolver.openOutputStream(saf(location).uri(), if (truncate) "rwt" else "wa")
            ?: throw FileAccessException("Não foi possível abrir para escrita")

    override suspend fun create(parent: FileLocation, name: String): FileNode {
        PathSecurity.validateName(name)
        val p = saf(parent)
        val uri = DocumentsContract.createDocument(resolver, p.uri(), "application/octet-stream", name)
            ?: throw FileAccessException("O provedor recusou a criação")
        return stat(FileLocation.Saf(uri.toString(), p.treeUri,
            listOf(FileLocation.SafParent(p.documentUri, p.displayPath)) + p.parents,
            "${p.displayPath.trimEnd('/')}/$name"))
    }

    override suspend fun mkdir(parent: FileLocation, name: String): FileNode {
        PathSecurity.validateName(name)
        val p = saf(parent)
        val uri = DocumentsContract.createDocument(resolver, p.uri(), DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw FileAccessException("O provedor recusou a criação da pasta")
        return stat(FileLocation.Saf(uri.toString(), p.treeUri,
            listOf(FileLocation.SafParent(p.documentUri, p.displayPath)) + p.parents,
            "${p.displayPath.trimEnd('/')}/$name"))
    }

    override suspend fun rename(source: FileLocation, newName: String): FileNode {
        PathSecurity.validateName(newName)
        val s = saf(source)
        val uri = DocumentsContract.renameDocument(resolver, s.uri(), newName)
            ?: throw FileAccessException("O provedor recusou a renomeação")
        val display = s.displayPath.substringBeforeLast('/', "") + "/" + newName
        return stat(FileLocation.Saf(uri.toString(), s.treeUri, s.parents, display))
    }

    override suspend fun delete(location: FileLocation) {
        if (!DocumentsContract.deleteDocument(resolver, saf(location).uri())) throw FileAccessException("Falha ao excluir")
    }

    override suspend fun parent(location: FileLocation): FileLocation? {
        val item = saf(location)
        val parent = item.parents.firstOrNull() ?: return null
        return FileLocation.Saf(parent.documentUri, item.treeUri, item.parents.drop(1), parent.displayPath)
    }

    private fun queryLong(uri: Uri, column: String): Long {
        resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
        }
        return 0
    }

    companion object {
        fun fromTreeUri(uri: Uri): FileLocation.Saf {
            val documentId = DocumentsContract.getTreeDocumentId(uri)
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
            return FileLocation.Saf(documentUri.toString(), uri.toString(), emptyList(), "SAF:/$documentId")
        }
    }
}
