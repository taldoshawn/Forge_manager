package com.forgemanager.app.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.forgemanager.app.BuildConfig
import rikka.shizuku.Shizuku

class ShizukuBridge(private val context: Context) {
    @Volatile var service: IPrivilegedFileService? = null
        private set
    @Volatile var binderAlive: Boolean = false
        private set

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, PrivilegedFileService::class.java.name))
            .daemon(false)
            .processNameSuffix("privileged")
            .debuggable(BuildConfig.DEBUG)
            .version(1)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IPrivilegedFileService.Stub.asInterface(binder)
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        binderAlive = true
        if (hasPermission()) bindService()
    }
    private val binderDead = Shizuku.OnBinderDeadListener {
        binderAlive = false
        service = null
    }

    fun start() {
        // Shizuku is an optional backend. Some ROMs/devices can expose an incomplete
        // provider/binder environment; none of those failures may crash app startup.
        runCatching { Shizuku.addBinderReceivedListenerSticky(binderReceived) }
        runCatching { Shizuku.addBinderDeadListener(binderDead) }
        binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (binderAlive && hasPermission()) bindService()
    }

    fun stop() {
        runCatching { Shizuku.removeBinderReceivedListener(binderReceived) }
        runCatching { Shizuku.removeBinderDeadListener(binderDead) }
        service = null
        binderAlive = false
    }

    fun hasPermission(): Boolean = binderAlive && runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission(requestCode: Int) {
        if (!binderAlive) throw IllegalStateException("Shizuku não está em execução")
        if (runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)) {
            throw SecurityException("Permissão Shizuku negada permanentemente")
        }
        runCatching { Shizuku.requestPermission(requestCode) }
            .getOrElse { throw IllegalStateException("Não foi possível solicitar permissão ao Shizuku", it) }
    }

    fun bindService() {
        if (service == null && hasPermission()) {
            runCatching { Shizuku.bindUserService(args, connection) }
                .onFailure {
                    service = null
                    binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
                }
        }
    }

    fun status(): String = when {
        !binderAlive -> "Shizuku desconectado"
        !hasPermission() -> "Shizuku sem permissão"
        service == null -> "Shizuku conectando"
        else -> "Shizuku conectado (UID ${runCatching { service?.serviceUid() }.getOrNull()})"
    }
}
