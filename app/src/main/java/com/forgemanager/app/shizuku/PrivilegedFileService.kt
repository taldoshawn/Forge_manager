package com.forgemanager.app.shizuku

import android.os.ParcelFileDescriptor
import android.system.Os
import com.forgemanager.app.core.security.PathSecurity
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Base64

class PrivilegedFileService : IPrivilegedFileService.Stub() {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    override fun list(path: String, showHidden: Boolean): Array<String> {
        val directory = checked(path)
        require(directory.isDirectory) { "Not a directory" }
        return (directory.listFiles() ?: throw SecurityException("Cannot list directory"))
            .asSequence()
            .filter { showHidden || !it.name.startsWith('.') }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .map(::encodeStat)
            .toList()
            .toTypedArray()
    }

    override fun stat(path: String): String = encodeStat(checked(path))

    override fun openRead(path: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(checked(path), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun openWrite(path: String, truncate: Boolean): ParcelFileDescriptor {
        var mode = ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE
        mode = mode or if (truncate) ParcelFileDescriptor.MODE_TRUNCATE else ParcelFileDescriptor.MODE_APPEND
        return ParcelFileDescriptor.open(checked(path), mode)
    }

    override fun create(parent: String, name: String): Boolean =
        PathSecurity.resolveChild(checked(parent), name).createNewFile()

    override fun mkdir(parent: String, name: String): Boolean =
        PathSecurity.resolveChild(checked(parent), name).mkdir()

    override fun rename(source: String, target: String): Boolean = checked(source).renameTo(checked(target))

    override fun deleteRecursively(path: String): Boolean {
        deleteNoFollow(checked(path))
        return true
    }

    override fun serviceUid(): Int = Os.getuid()

    override fun destroy() {
        System.exit(0)
    }

    private fun checked(path: String): File {
        require(path.isNotBlank() && path.length <= 4096 && !path.contains('\u0000')) { "Invalid path" }
        return File(path)
    }

    private fun deleteNoFollow(file: File) {
        if (Files.isSymbolicLink(file.toPath())) {
            Files.delete(file.toPath())
            return
        }
        if (file.isDirectory) file.listFiles()?.forEach(::deleteNoFollow)
        if (!file.delete()) throw IllegalStateException("Delete failed")
    }

    private fun encodeStat(file: File): String {
        val path = encoder.encodeToString(file.absolutePath.toByteArray(StandardCharsets.UTF_8))
        val name = encoder.encodeToString(file.name.toByteArray(StandardCharsets.UTF_8))
        val isLink = Files.isSymbolicLink(file.toPath())
        val isDirectory = Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        return listOf(path, name, if (isDirectory) "1" else "0", file.length().toString(),
            file.lastModified().toString(), if (isLink) "1" else "0").joinToString("\t")
    }
}
