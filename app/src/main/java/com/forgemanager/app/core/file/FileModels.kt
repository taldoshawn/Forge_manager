package com.forgemanager.app.core.file

import android.net.Uri
import java.io.InputStream
import java.io.OutputStream

sealed interface FileLocation {
    val displayPath: String

    data class Direct(val path: String) : FileLocation {
        override val displayPath: String = path
    }

    data class Saf(
        val documentUri: String,
        val treeUri: String,
        val parents: List<SafParent> = emptyList(),
        override val displayPath: String
    ) : FileLocation {
        fun uri(): Uri = Uri.parse(documentUri)
    }

    data class SafParent(val documentUri: String, val displayPath: String)

    data class Archive(
        val archivePath: String,
        val entryPath: String = ""
    ) : FileLocation {
        override val displayPath: String = "$archivePath!/${entryPath.trimStart('/')}"
    }
}

data class FileNode(
    val location: FileLocation,
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val modified: Long = 0,
    val mimeType: String? = null,
    val permissions: String? = null,
    val isSymlink: Boolean = false
)

data class BackendCapabilities(
    val read: Boolean = true,
    val write: Boolean = false,
    val randomAccess: Boolean = false,
    val permissions: Boolean = false,
    val symlinks: Boolean = false
)

data class OperationProgress(
    val itemsDone: Int,
    val itemsTotal: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
    val currentName: String
)

enum class ConflictPolicy { ASK, REPLACE, SKIP, RENAME }

fun interface ProgressListener {
    fun onProgress(progress: OperationProgress)
}

interface FileBackend {
    val id: String
    fun supports(location: FileLocation): Boolean
    suspend fun capabilities(location: FileLocation): BackendCapabilities
    suspend fun list(location: FileLocation, showHidden: Boolean = true): List<FileNode>
    suspend fun stat(location: FileLocation): FileNode
    suspend fun openInput(location: FileLocation): InputStream
    suspend fun openOutput(location: FileLocation, truncate: Boolean = true): OutputStream
    suspend fun create(parent: FileLocation, name: String): FileNode
    suspend fun mkdir(parent: FileLocation, name: String): FileNode
    suspend fun rename(source: FileLocation, newName: String): FileNode
    suspend fun delete(location: FileLocation)
    suspend fun parent(location: FileLocation): FileLocation?
}

class FileAccessException(message: String, cause: Throwable? = null) : Exception(message, cause)
