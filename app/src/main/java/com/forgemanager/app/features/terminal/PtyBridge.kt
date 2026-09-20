package com.forgemanager.app.features.terminal

object PtyBridge {
    init { System.loadLibrary("forgepty") }

    external fun spawn(executable: String, cwd: String, rows: Int, cols: Int): Long
    external fun resize(fd: Int, rows: Int, cols: Int): Int
    external fun signal(pid: Int, signal: Int): Int
    external fun waitPid(pid: Int): Int

    fun unpackPid(handle: Long): Int = (handle ushr 32).toInt()
    fun unpackFd(handle: Long): Int = handle.toInt()
}
