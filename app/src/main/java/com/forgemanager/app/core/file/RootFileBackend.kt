package com.forgemanager.app.core.file

import com.forgemanager.app.core.security.PathSecurity
import com.forgemanager.app.core.shell.ShellEscaper
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class RootFileBackend : FileBackend {
    override val id: String = "root"
    @Volatile private var authorized = false

    override fun supports(location: FileLocation): Boolean = location is FileLocation.Direct && authorized

    fun isAuthorized(): Boolean = authorized

    fun authorize(): Boolean {
        val result = runCommand("id -u", timeoutSeconds = 20)
        authorized = result.exitCode == 0 && result.stdout.toString(StandardCharsets.UTF_8).trim() == "0"
        return authorized
    }

    override suspend fun capabilities(location: FileLocation) = BackendCapabilities(
        read = authorized, write = authorized, randomAccess = false, permissions = true, symlinks = true
    )

    override suspend fun list(location: FileLocation, showHidden: Boolean): List<FileNode> {
        requireAuthorized()
        val directory = path(location)
        val result = runCommand("find ${ShellEscaper.quote(directory)} -mindepth 1 -maxdepth 1 -print0")
        if (result.exitCode != 0) throw FileAccessException(result.errorText("Falha ao listar com root"))
        return splitNul(result.stdout).asSequence()
            .map { String(it, StandardCharsets.UTF_8) }
            .filter { showHidden || !File(it).name.startsWith('.') }
            .map { rootStat(it) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .toList()
    }

    override suspend fun stat(location: FileLocation): FileNode = rootStat(path(location))

    override suspend fun openInput(location: FileLocation): InputStream {
        requireAuthorized()
        val process = ProcessBuilder("su", "-c", "cat -- ${ShellEscaper.quote(path(location))}").start()
        return object : FilterInputStream(process.inputStream) {
            override fun close() {
                super.close()
                if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroyForcibly()
                if (process.exitValue() != 0) throw FileAccessException("Leitura root falhou")
            }
        }
    }

    override suspend fun openOutput(location: FileLocation, truncate: Boolean): OutputStream {
        requireAuthorized()
        val redirect = if (truncate) ">" else ">>"
        val process = ProcessBuilder("su", "-c", "cat $redirect ${ShellEscaper.quote(path(location))}").start()
        return object : FilterOutputStream(process.outputStream) {
            override fun close() {
                super.close()
                if (!process.waitFor(30, TimeUnit.SECONDS)) process.destroyForcibly()
                if (process.exitValue() != 0) throw FileAccessException("Escrita root falhou")
            }
        }
    }

    override suspend fun create(parent: FileLocation, name: String): FileNode {
        PathSecurity.validateName(name)
        val target = File(path(parent), name).path
        checkedCommand("set -C; : > ${ShellEscaper.quote(target)}", "Falha ao criar arquivo com root")
        return rootStat(target)
    }

    override suspend fun mkdir(parent: FileLocation, name: String): FileNode {
        PathSecurity.validateName(name)
        val target = File(path(parent), name).path
        checkedCommand("mkdir -- ${ShellEscaper.quote(target)}", "Falha ao criar pasta com root")
        return rootStat(target)
    }

    override suspend fun rename(source: FileLocation, newName: String): FileNode {
        PathSecurity.validateName(newName)
        val sourcePath = path(source)
        val target = File(File(sourcePath).parentFile, newName).path
        checkedCommand("test ! -e ${ShellEscaper.quote(target)} && mv -- ${ShellEscaper.quote(sourcePath)} ${ShellEscaper.quote(target)}", "Falha ao renomear com root")
        return rootStat(target)
    }

    override suspend fun delete(location: FileLocation) {
        val target = path(location)
        require(target != "/" && target.isNotBlank()) { "Exclusão da raiz bloqueada" }
        checkedCommand("rm -rf -- ${ShellEscaper.quote(target)}", "Falha ao excluir com root")
    }

    override suspend fun parent(location: FileLocation): FileLocation? = File(path(location)).parent?.let { FileLocation.Direct(it) }

    /** Changes Unix mode only after explicit root authorization. Accepts 3 or 4 octal digits. */
    fun chmod(location: FileLocation, mode: String) {
        requireAuthorized()
        require(mode.matches(Regex("^[0-7]{3,4}$"))) { "Modo inválido. Use 3 ou 4 dígitos octais, por exemplo 644 ou 0755." }
        checkedCommand("chmod $mode -- ${ShellEscaper.quote(path(location))}", "Falha ao alterar permissões com root")
    }

    private fun rootStat(path: String): FileNode {
        val result = runCommand("stat -c '%A|%a|%U|%G|%F|%s|%Y' -- ${ShellEscaper.quote(path)}")
        if (result.exitCode != 0) throw FileAccessException(result.errorText("Falha ao obter informações com root"))
        val parts = result.stdout.toString(StandardCharsets.UTF_8).trim().split('|')
        if (parts.size < 7) throw FileAccessException("Resposta stat inválida")
        return FileNode(
            location = FileLocation.Direct(path),
            name = File(path).name.ifEmpty { path },
            isDirectory = parts[4].contains("directory"),
            size = parts[5].toLongOrNull() ?: 0,
            modified = (parts[6].toLongOrNull() ?: 0) * 1000,
            permissions = "${parts[0]} (${parts[1]})  ${parts[2]}:${parts[3]}",
            isSymlink = parts[4].contains("symbolic link")
        )
    }

    private fun checkedCommand(command: String, message: String) {
        requireAuthorized()
        val result = runCommand(command)
        if (result.exitCode != 0) throw FileAccessException(result.errorText(message))
    }

    private fun path(location: FileLocation) = (location as? FileLocation.Direct)?.path
        ?: throw FileAccessException("Caminho root inválido")
    private fun requireAuthorized() { if (!authorized) throw FileAccessException("Root não autorizado") }

    private fun runCommand(command: String, timeoutSeconds: Long = 30): CommandResult {
        val process = ProcessBuilder("su", "-c", command).start()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val outThread = Thread { process.inputStream.use { it.copyTo(stdout) } }.apply { start() }
        val errThread = Thread { process.errorStream.use { it.copyTo(stderr) } }.apply { start() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw FileAccessException("Comando root excedeu o tempo limite")
        }
        outThread.join(2_000)
        errThread.join(2_000)
        return CommandResult(process.exitValue(), stdout.toByteArray(), stderr.toByteArray())
    }

    private fun splitNul(bytes: ByteArray): List<ByteArray> {
        val output = ArrayList<ByteArray>()
        var start = 0
        bytes.forEachIndexed { index, byte ->
            if (byte.toInt() == 0) {
                if (index > start) output += bytes.copyOfRange(start, index)
                start = index + 1
            }
        }
        return output
    }

    private data class CommandResult(val exitCode: Int, val stdout: ByteArray, val stderr: ByteArray) {
        fun errorText(fallback: String): String = stderr.toString(StandardCharsets.UTF_8).trim().take(500).ifEmpty { fallback }
    }
}
