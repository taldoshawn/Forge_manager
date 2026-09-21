package com.forgemanager.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.forgemanager.app.archive.ArchiveFileBackend
import com.forgemanager.app.core.file.AccessResolver
import com.forgemanager.app.core.file.DirectFileBackend
import com.forgemanager.app.core.file.RootFileBackend
import com.forgemanager.app.core.file.SafFileBackend
import com.forgemanager.app.core.file.ShizukuFileBackend
import com.forgemanager.app.features.explorer.ExplorerUiEnhancer
import com.forgemanager.app.shizuku.ShizukuBridge

class ForgeApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // Shizuku is optional. A missing/unsupported Shizuku environment must never
        // prevent the file manager itself from starting.
        runCatching { graph.shizuku.start() }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = ExplorerUiEnhancer.apply(activity)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
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
