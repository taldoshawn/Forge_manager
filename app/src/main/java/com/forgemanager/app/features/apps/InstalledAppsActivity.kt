package com.forgemanager.app.features.apps

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class InstalledAppsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var list: ListView
    private var apps: List<PackageInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Aplicativos instalados"
        list = ListView(this)
        setContentView(list)
        load()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    @Suppress("DEPRECATION")
    private fun load() {
        scope.launch {
            apps = withContext(Dispatchers.IO) {
                val flags = PackageManager.GET_META_DATA
                val result = if (Build.VERSION.SDK_INT >= 33) packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
                else packageManager.getInstalledPackages(flags)
                result.sortedBy { packageManager.getApplicationLabel(it.applicationInfo!!).toString().lowercase() }
            }
            list.adapter = ArrayAdapter(this@InstalledAppsActivity, android.R.layout.simple_list_item_2, android.R.id.text1,
                apps.map { info -> "${packageManager.getApplicationLabel(info.applicationInfo!!)}\n${info.packageName}  ${info.versionName ?: ""}" })
            list.setOnItemClickListener { _, _, position, _ -> showActions(apps[position]) }
        }
    }

    private fun showActions(info: PackageInfo) {
        val system = info.applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) != 0
        val splits = info.applicationInfo?.splitSourceDirs?.size ?: 0
        AlertDialog.Builder(this).setTitle(packageManager.getApplicationLabel(info.applicationInfo!!))
            .setMessage("${info.packageName}\n${if (system) "Sistema" else "Usuário"}\nSplits: $splits")
            .setItems(arrayOf("Abrir", "Detalhes do sistema", "Exportar e compartilhar", "Desinstalar")) { _, which ->
                when (which) {
                    0 -> packageManager.getLaunchIntentForPackage(info.packageName)?.let(::startActivity) ?: toast("Aplicativo sem tela inicial")
                    1 -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${info.packageName}")))
                    2 -> export(info)
                    3 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${info.packageName}")))
                }
            }.show()
    }

    private fun export(info: PackageInfo) {
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                val dir = File(cacheDir, "shared").apply { mkdirs() }
                val splits = listOfNotNull(info.applicationInfo?.sourceDir) + info.applicationInfo?.splitSourceDirs.orEmpty()
                if (splits.size == 1) {
                    val output = File(dir, "${info.packageName}-${info.versionName}.apk")
                    FileInputStream(splits.first()).use { input -> FileOutputStream(output).use { input.copyTo(it, 128 * 1024); it.fd.sync() } }
                    output
                } else {
                    val output = File(dir, "${info.packageName}-${info.versionName}-splits.zip")
                    FileOutputStream(output).use { raw -> ZipOutputStream(raw.buffered()).use { zip ->
                        splits.forEachIndexed { index, path ->
                            val name = if (index == 0) "base.apk" else File(path).name
                            zip.putNextEntry(ZipEntry(name)); FileInputStream(path).use { it.copyTo(zip, 128 * 1024) }; zip.closeEntry()
                        }
                    }}
                    output
                }
            }}
            result.onSuccess { file ->
                val uri = FileProvider.getUriForFile(this@InstalledAppsActivity, "$packageName.files", file)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(if (file.extension == "apk") "application/vnd.android.package-archive" else "application/zip")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Compartilhar"))
            }.onFailure { toast(it.message ?: "Falha ao exportar") }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
