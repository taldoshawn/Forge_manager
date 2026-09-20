package com.forgemanager.app

import android.app.Application
import com.forgemanager.app.archive.ArchiveFileBackend
import com.forgemanager.app.core.file.AccessResolver
import com.forgemanager.app.core.file.DirectFileBackend
import com.forgemanager.app.core.file.RootFileBackend
import com.forgemanager.app.core.file.SafFileBackend
import com.forgemanager.app.core.file.ShizukuFileBackend
import com.forgemanager.app.shizuku.ShizukuBridge

class ForgeApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.shizuku.start()
    }
}

class AppGraph(application: Application) {
    val shizuku = ShizukuBridge(application)
    val direct = DirectFileBackend()
    val saf = SafFileBackend(application)
    val root = RootFileBackend()
    val archive = ArchiveFileBackend(application)
    val shizukuBackend = ShizukuFileBackend(shizuku)
    val resolver = AccessResolver(direct, saf, shizukuBackend, root, archive)
}
