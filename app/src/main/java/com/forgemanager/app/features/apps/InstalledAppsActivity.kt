package com.forgemanager.app.features.apps

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.forgemanager.app.core.ui.ForgeActivity
import com.forgemanager.app.features.settings.UiPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class InstalledAppsActivity : ForgeActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var list: ListView
    private lateinit var status: TextView
    private lateinit var search: EditText
    private var apps: List<PackageInfo> = emptyList()
    private var shownApps: List<PackageInfo> = emptyList()
    private var mode: String = MODE_BROWSE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_BROWSE
        setContentView(buildUi())
        load()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(UiPreferences.background(this@InstalledAppsActivity))

        val top = LinearLayout(this@InstalledAppsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(UiPreferences.surface(this@InstalledAppsActivity))
        }
        top.addView(button("←") { finish() })
        status = TextView(this@InstalledAppsActivity).apply {
            text = if (mode == MODE_EXTRACT) "Extrair APKs instalados" else "Aplicativos instalados"
            textSize = 15f
            maxLines = 2
            setTextColor(UiPreferences.textPrimary(this@InstalledAppsActivity))
            setPadding(dp(6), 0, dp(6), 0)
        }
        top.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(button("↓USR") { confirmBulkExtract() })
        addView(top, LinearLayout.LayoutParams(-1, dp(54)))

        search = EditText(this@InstalledAppsActivity).apply {
            hint = "Buscar app ou pacote"
            setSingleLine()
            textSize = 13f
            setTextColor(UiPreferences.textPrimary(this@InstalledAppsActivity))
            setHintTextColor(UiPreferences.textSecondary(this@InstalledAppsActivity))
            setBackgroundColor(UiPreferences.elevatedSurface(this@InstalledAppsActivity))
            setPadding(dp(12), 0, dp(12), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = filterApps()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        addView(search, LinearLayout.LayoutParams(-1, dp(48)))

        list = ListView(this@InstalledAppsActivity).apply {
            setBackgroundColor(UiPreferences.background(this@InstalledAppsActivity))
            dividerHeight = 0
            setOnItemClickListener { _, _, position, _ -> shownApps.getOrNull(position)?.let(::showActions) }
        }
        addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = if (label.length > 2) 9f else 16f
        setTextColor(UiPreferences.textPrimary(this@InstalledAppsActivity))
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        minWidth = dp(48)
        setOnClickListener { action() }
    }

    @Suppress("DEPRECATION")
    private fun load() {
        status.text = "Lendo aplicativos…"
        scope.launch {
            apps = withContext(Dispatchers.IO) {
                val flags = PackageManager.GET_META_DATA
                val result = if (Build.VERSION.SDK_INT >= 33) {
                    packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
                } else packageManager.getInstalledPackages(flags)
                result.filter { it.applicationInfo != null }
                    .sortedBy { packageManager.getApplicationLabel(it.applicationInfo!!).toString().lowercase(Locale.ROOT) }
            }
            filterApps()
        }
    }

    private fun filterApps() {
        if (!::list.isInitialized) return
        val query = search.text?.toString().orEmpty().trim()
        shownApps = if (query.isBlank()) apps else apps.filter { info ->
            val label = runCatching { packageManager.getApplicationLabel(info.applicationInfo!!).toString() }.getOrDefault("")
            label.contains(query, true) || info.packageName.contains(query, true)
        }
        list.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_2,
            android.R.id.text1,
            shownApps.map { info ->
                val label = packageManager.getApplicationLabel(info.applicationInfo!!)
                val system = info.applicationInfo!!.flags and ApplicationInfo.FLAG_SYSTEM != 0
                val splitCount = info.applicationInfo!!.splitSourceDirs?.size ?: 0
                "$label\n${info.packageName}  ${info.versionName ?: ""}  •  ${if (system) "sistema" else "usuário"}${if (splitCount > 0) "  •  ${splitCount + 1} APKs" else ""}"
            }
        )
        status.text = if (mode == MODE_EXTRACT) "Extrair APKs • ${shownApps.size} apps" else "Aplicativos • ${shownApps.size}"
    }

    private fun showActions(info: PackageInfo) {
        val appInfo = info.applicationInfo ?: return
        val system = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
        val splits = appInfo.splitSourceDirs?.size ?: 0
        val label = packageManager.getApplicationLabel(appInfo).toString()
        val actions = arrayOf(
            "Extrair pacote → Downloads",
            "Extrair somente base.apk → Downloads",
            "Exportar e compartilhar",
            "Abrir aplicativo",
            "Detalhes do sistema",
            "Desinstalar"
        )
        AlertDialog.Builder(this)
            .setTitle(label)
            .setMessage("${info.packageName}\n${if (system) "Sistema" else "Usuário"}\nAPKs: ${splits + 1}")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> extractToDownloads(info, baseOnly = false)
                    1 -> extractToDownloads(info, baseOnly = true)
                    2 -> shareExport(info)
                    3 -> packageManager.getLaunchIntentForPackage(info.packageName)?.let(::startActivity)
                        ?: toast("Aplicativo sem tela inicial")
                    4 -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${info.packageName}")))
                    5 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${info.packageName}")))
                }
            }
            .show()
    }

    private fun extractToDownloads(info: PackageInfo, baseOnly: Boolean) {
        status.text = "Extraindo ${info.packageName}…"
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { extractPackage(info, baseOnly) } }
            result.onSuccess { file ->
                status.text = "Extraído: ${file.name}"
                AlertDialog.Builder(this@InstalledAppsActivity)
                    .setTitle("APK extraído")
                    .setMessage("Salvo em:\n${file.path}\n\n${if (file.extension.equals("apks", true)) "O app usa split APKs; o arquivo .apks contém base.apk + splits." else "APK pronto para uso."}")
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Compartilhar") { _, _ -> shareFile(file) }
                    .show()
            }.onFailure {
                status.text = "Falha ao extrair"
                toast(it.message ?: "Falha ao extrair APK")
            }
        }
    }

    private fun confirmBulkExtract() {
        val userApps = apps.filter { info ->
            val flags = info.applicationInfo?.flags ?: 0
            flags and ApplicationInfo.FLAG_SYSTEM == 0
        }
        if (userApps.isEmpty()) {
            toast("Nenhum app de usuário encontrado")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Extrair apps de usuário")
            .setMessage("Extrair ${userApps.size} aplicativo(s) para Download/ForgeManager/APKs? Apps com splits serão salvos como .apks.")
            .setPositiveButton("Extrair") { _, _ -> bulkExtract(userApps) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun bulkExtract(values: List<PackageInfo>) {
        scope.launch {
            status.text = "Extração em lote 0/${values.size}"
            val result = withContext(Dispatchers.IO) {
                var ok = 0
                val failures = ArrayList<String>()
                values.forEachIndexed { index, info ->
                    runCatching { extractPackage(info, baseOnly = false) }
                        .onSuccess { ok++ }
                        .onFailure { failures += info.packageName }
                    withContext(Dispatchers.Main) { status.text = "Extração em lote ${index + 1}/${values.size}" }
                }
                ok to failures
            }
            status.text = "Extração concluída • ${result.first}/${values.size}"
            AlertDialog.Builder(this@InstalledAppsActivity)
                .setTitle("Extração em lote concluída")
                .setMessage(buildString {
                    append("Extraídos: ${result.first}/${values.size}\nDestino: ${downloadsDirectory().path}")
                    if (result.second.isNotEmpty()) append("\n\nFalharam: ").append(result.second.take(20).joinToString(", "))
                })
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun extractPackage(info: PackageInfo, baseOnly: Boolean): File {
        val appInfo = info.applicationInfo ?: error("ApplicationInfo indisponível")
        val base = appInfo.sourceDir?.let(::File)?.takeIf { it.isFile } ?: error("base.apk não encontrado")
        val splits = appInfo.splitSourceDirs.orEmpty().map(::File).filter { it.isFile }
        val destination = downloadsDirectory().apply {
            if (!exists() && !mkdirs()) error("Não foi possível criar ${path}")
        }
        val version = sanitize(info.versionName ?: "unknown")
        val stem = sanitize("${info.packageName}-$version")

        if (baseOnly || splits.isEmpty()) {
            val output = uniqueFile(destination, "$stem.apk")
            copyFile(base, output)
            return output
        }

        val output = uniqueFile(destination, "$stem.apks")
        FileOutputStream(output).use { raw ->
            ZipOutputStream(raw.buffered(128 * 1024)).use { zip ->
                addZipFile(zip, base, "base.apk")
                splits.forEach { split -> addZipFile(zip, split, sanitize(split.name)) }
            }
            raw.fd.sync()
        }
        return output
    }

    private fun shareExport(info: PackageInfo) {
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                val dir = File(cacheDir, "shared-apks").apply { mkdirs() }
                val appInfo = info.applicationInfo ?: error("ApplicationInfo indisponível")
                val base = appInfo.sourceDir?.let(::File)?.takeIf { it.isFile } ?: error("base.apk não encontrado")
                val splits = appInfo.splitSourceDirs.orEmpty().map(::File).filter { it.isFile }
                val stem = sanitize("${info.packageName}-${info.versionName ?: "unknown"}")
                if (splits.isEmpty()) {
                    File(dir, "$stem.apk").also { copyFile(base, it) }
                } else {
                    File(dir, "$stem.apks").also { output ->
                        FileOutputStream(output).use { raw ->
                            ZipOutputStream(raw.buffered(128 * 1024)).use { zip ->
                                addZipFile(zip, base, "base.apk")
                                splits.forEach { addZipFile(zip, it, sanitize(it.name)) }
                            }
                            raw.fd.sync()
                        }
                    }
                }
            }}
            result.onSuccess(::shareFile).onFailure { toast(it.message ?: "Falha ao exportar") }
        }
    }

    private fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val mime = if (file.extension.equals("apk", true)) "application/vnd.android.package-archive" else "application/zip"
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(send, "Compartilhar pacote")) }
            .onFailure { toast("Nenhum aplicativo compatível") }
    }

    private fun addZipFile(zip: ZipOutputStream, source: File, entryName: String) {
        zip.putNextEntry(ZipEntry(entryName))
        FileInputStream(source).buffered(128 * 1024).use { it.copyTo(zip, 128 * 1024) }
        zip.closeEntry()
    }

    private fun copyFile(source: File, target: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output, 128 * 1024)
                output.flush()
                output.fd.sync()
            }
        }
    }

    private fun downloadsDirectory() = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "ForgeManager/APKs"
    )

    private fun uniqueFile(parent: File, name: String): File {
        val requested = File(parent, name)
        if (!requested.exists()) return requested
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (index < 10_000) {
            val candidate = File(parent, "$stem ($index)$ext")
            if (!candidate.exists()) return candidate
            index++
        }
        error("Muitos arquivos com o mesmo nome")
    }

    private fun sanitize(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._() -]"), "_")
        .trim().trim('.').take(140).ifBlank { "package" }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_BROWSE = "browse"
        const val MODE_EXTRACT = "extract"
    }
}