package com.forgemanager.app.features.terminal

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class PtySession(
    executable: String,
    cwd: String,
    rows: Int,
    cols: Int,
    private val onOutput: (String) -> Unit,
    private val onExit: (Int) -> Unit
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val pid: Int
    private val masterFd: Int
    private val controlPfd: ParcelFileDescriptor
    private val readPfd: ParcelFileDescriptor
    private val writePfd: ParcelFileDescriptor
    private val input: FileInputStream
    private val output: FileOutputStream
    private val readerThread: Thread
    private val waiterThread: Thread

    init {
        val handle = PtyBridge.spawn(executable, cwd, rows.coerceAtLeast(2), cols.coerceAtLeast(10))
        require(handle != 0L) { "Não foi possível criar a PTY" }
        pid = PtyBridge.unpackPid(handle)
        masterFd = PtyBridge.unpackFd(handle)
        require(pid > 0 && masterFd >= 0) { "Handle PTY inválido" }

        val original = ParcelFileDescriptor.adoptFd(masterFd)
        controlPfd = ParcelFileDescriptor.dup(original.fileDescriptor)
        readPfd = ParcelFileDescriptor.dup(original.fileDescriptor)
        writePfd = ParcelFileDescriptor.dup(original.fileDescriptor)
        original.close()
        input = FileInputStream(readPfd.fileDescriptor)
        output = FileOutputStream(writePfd.fileDescriptor)

        readerThread = Thread({ readLoop() }, "forge-pty-reader-$pid").apply { isDaemon = true; start() }
        waiterThread = Thread({
            val code = PtyBridge.waitPid(pid)
            if (!closed.get()) onExit(code)
        }, "forge-pty-wait-$pid").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun send(value: String) {
        if (closed.get()) return
        runCatching {
            output.write(value.toByteArray(StandardCharsets.UTF_8))
            output.flush()
        }
    }

    fun resize(rows: Int, cols: Int) {
        if (!closed.get()) PtyBridge.resize(controlPfd.fd, rows.coerceAtLeast(2), cols.coerceAtLeast(10))
    }

    fun interrupt() { if (!closed.get()) PtyBridge.signal(pid, 2) }

    private fun readLoop() {
        val reader = InputStreamReader(input, StandardCharsets.UTF_8)
        val buffer = CharArray(4096)
        try {
            while (!closed.get()) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (count > 0) onOutput(String(buffer, 0, count))
            }
        } catch (_: Throwable) {
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { PtyBridge.signal(pid, 15) }
        runCatching { output.close() }
        runCatching { input.close() }
        runCatching { writePfd.close() }
        runCatching { readPfd.close() }
        runCatching { controlPfd.close() }
    }
}
