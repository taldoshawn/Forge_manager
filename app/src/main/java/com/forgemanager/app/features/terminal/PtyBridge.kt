package com.forgemanager.app.features.terminal

import java.io.Closeable
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean

class PtyBridge {
    init {
        System.loadLibrary("forgepty")
    }

    external fun nativeSpawn(
        argv: Array<String>,
        cwd: String,
        environment: Array<String>,
        rows: Int,
        cols: Int
    ): LongArray

    external fun nativeRead(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int
    external fun nativeWrite(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int
    external fun nativeResize(fd: Int, rows: Int, cols: Int)
    external fun nativeWait(pid: Int, block: Boolean): Int
    external fun nativeSignal(pid: Int, signal: Int)
    external fun nativeClose(fd: Int)

    fun spawn(
        argv: Array<String>,
        cwd: String,
        environment: Map<String, String>,
        rows: Int = 24,
        cols: Int = 80
    ): PtySession {
        require(argv.isNotEmpty()) { "argv vazio" }
        val safeEnvironment = environment.entries.map { (key, value) ->
            require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Variável de ambiente inválida" }
            require(!value.contains('\u0000')) { "Valor de ambiente inválido" }
            "$key=$value"
        }.toTypedArray()
        val values = nativeSpawn(argv, cwd, safeEnvironment, rows, cols)
        require(values.size >= 2) { "Resposta PTY inválida" }
        return PtySession(this, values[0].toInt(), values[1].toInt())
    }

    class PtySession internal constructor(
        private val bridge: PtyBridge,
        val fd: Int,
        val pid: Int
    ) : Closeable {
        private val closed = AtomicBoolean(false)

        fun read(buffer: ByteArray): Int {
            check(!closed.get()) { "PTY encerrado" }
            return bridge.nativeRead(fd, buffer, 0, buffer.size)
        }

        fun write(bytes: ByteArray) {
            if (closed.get() || bytes.isEmpty()) return
            bridge.nativeWrite(fd, bytes, 0, bytes.size)
        }

        fun write(text: String, charset: Charset = Charsets.UTF_8) = write(text.toByteArray(charset))

        fun resize(rows: Int, cols: Int) {
            if (!closed.get()) bridge.nativeResize(fd, rows.coerceAtLeast(2), cols.coerceAtLeast(10))
        }

        fun waitFor(block: Boolean = true): Int = bridge.nativeWait(pid, block)

        fun signal(signal: Int) {
            if (!closed.get()) bridge.nativeSignal(pid, signal)
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                runCatching { bridge.nativeSignal(pid, SIGTERM) }
                runCatching { bridge.nativeClose(fd) }
            }
        }
    }

    companion object {
        const val SIGINT = 2
        const val SIGTERM = 15
        const val SIGKILL = 9
    }
}
