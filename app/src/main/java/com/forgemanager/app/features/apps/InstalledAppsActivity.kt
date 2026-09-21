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
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
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
    private var pendingExtract: PendingExtract? = null
    private var pendingBulk = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_BROWSE
        setContentView(buildUi())
        load()
    }

    override fun onResume() {
        super.onResume()
        if (!hasSharedStorageAccess()) return

        pendingExtract?.let { pending ->
            pendingExtract = null
            apps.firstOrNull { it.packageName == pending.packageName }?.let {
                extractToDownloads(it, pending.baseOnly, permissionChecked = true)
            }
        }
        if (pendingBulk && apps.isNotEmpty()) {
            pendingBulk = false
            confirmBulkExtract(permissionChecked = true)
        }
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
            setBackgroundColor(android.graphics.Color.rgb(15, 15, 15))
        }
        top.addView(button("←", light = true) { finish() })
        status = TextView(this@InstalledAppsActivity).apply {
            text = if (mode == MODE_EXTRACT) "Extrair APKs" else "Aplicativos instalados"
            textSize = 15f
            maxLines = 2
            setTextColor(android.graphics.Color.WHITE)
            setPadding(dp(6), 0, dp(6), 0)
        }
        top.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(button("↓USR", light = true) { confirmBulkExtract() })
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
            setOnItemClickListener { _, _, position, _ ->
                shownApps.getOrNull(position)?.let { info ->
                    if (mode == MODE_EXTRACT) extractToDownloads(info, baseOnly = false)
                    else showActions(info)
                }
            }
            setOnItemLongClickListener { _, _, position, _ ->
                shownApps.getOrNull(position)?.let(::showActions)
                true
            }
        }
        addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun button(label: String, light: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = if (label.length > 2) 9f else 16f
        setTextColor(if (light) android.graphics.Color.WHITE else UiPreferences.textPrimary(this@InstalledAppsActivity))
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
        list.adapter = AppsAdapter(shownApps)
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
        val heading = buildString {
            append(info.packageName)
            append("\n")
            append(if (system) "Sistema" else "Usuário")
            append("  •  APKs: ").append(splits + 1)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(4), dp(22), dp(4))
            addView(TextView(this@InstalledAppsActivity).apply {
                text = heading
                textSize = 13f
                setTextColor(UiPreferences.textSecondary(this@InstalledAppsActivity))
                setPadding(0, 0, 0, dp(10))
            })
        }
        AlertDialog.Builder(this)
            .setTitle(label)
            .setView(box)
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
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun extractToDownloads(info: PackageInfo, baseOnly: Boolean, permissionChecked: Boolean = false) {
        if (!permissionChecked && !ensureSharedStorageAccess(info, baseOnly)) return
        status.text = "Extraindo ${info.packageName}…"
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { extractPackage(info, baseOnly) } }
            result.onSuccess { file ->
                status.text = "Extraído: ${file.name}"
                AlertDialog.Builder(this@InstalledAppsActivity)
                    .setTitle("APK extraído")
                    .setMessage("Salvo em:\n${file.path}\n\n${if (file.extension.equals("apks", true)) "O app usa split APKs; o .apks contém base.apk + splits." else "APK pronto para uso."}")
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Compartilhar") { _, _ -> shareFile(file) }
                    .show()
            }.onFailure {
                status.text = "Falha ao extrair"
                showExtractionError(it)
            }
        }
    }

    private fun ensureSharedStorageAccess(info: PackageInfo, baseOnly: Boolean): Boolean {
        if (hasSharedStorageAccess()) return true
        pendingExtract = PendingExtract(info.packageName, baseOnly)
        AlertDialog.Builder(this)
            .setTitle("Permissão necessária")
            .setMessage("Para salvar o APK em Download/ForgeManager/APKs, permita ao Forge Manager acesso a todos os arquivos. Ao voltar para o app, a extração continua automaticamente.")
            .setPositiveButton("Permitir") { _, _ -> openAllFilesAccessSettings() }
            .setNegativeButton("Cancelar") { _, _ -> pendingExtract = null }
            .show()
        return false
    }

    private fun hasSharedStorageAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun openAllFilesAccessSettings() {
        val appUri = Uri.parse("package:$packageName")
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, appUri)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri)
        }
        runCatching { startActivity(intent) }
            .onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                    .onFailure { toast("Não foi possível abrir a tela de permissão") }
            }
    }

    private fun confirmBulkExtract(permissionChecked: Boolean = false) {
        if (!permissionChecked && !hasSharedStorageAccess()) {
            pendingBulk = true
            AlertDialog.Builder(this)
                .setTitle("Permissão necessária")
                .setMessage("A extração em lote salva em Download/ForgeManager/APKs. Permita acesso a todos os arquivos e volte para o Forge Manager.")
                .setPositiveButton("Permitir") { _, _ -> openAllFilesAccessSettings() }
                .setNegativeButton("Cancelar") { _, _ -> pendingBulk = false }
                .show()
            return
        }

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
            if (!exists() && !mkdirs()) error("Não foi possível criar $path")
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
            val result = runCatching {
                withContext(Dispatchers.IO) {
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
                }
            }
            result.onSuccess(::shareFile).onFailure(::showExtractionError)
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

    private fun showExtractionError(error: Throwable) {
        val message = error.message ?: "Falha ao extrair APK"
        AlertDialog.Builder(this)
            .setTitle("Falha ao extrair APK")
            .setMessage("$message\n\nSe o destino estiver em Downloads, confirme que o Forge Manager possui acesso a todos os arquivos.")
            .setPositiveButton("OK", null)
            .setNeutralButton("Permissões") { _, _ -> openAllFilesAccessSettings() }
            .show()
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

    private inner class AppsAdapter(private val values: List<PackageInfo>) : BaseAdapter() {
        override fun getCount(): Int = values.size
        override fun getItem(position: Int): PackageInfo = values[position]
        override fun getItemId(position: Int): Long = values[position].packageName.hashCode().toLong()
        override fun hasStableIds(): Boolean = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder: AppHolder
            val row = if (convertView == null) {
                LinearLayout(this@InstalledAppsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(5), dp(10), dp(5))
                    holder = AppHolder(
                        ImageView(this@InstalledAppsActivity).also { icon ->
                            icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
                            addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) })
                        },
                        TextView(this@InstalledAppsActivity).also { title ->
                            title.textSize = 13f
                            title.setTextColor(UiPreferences.textPrimary(this@InstalledAppsActivity))
                        },
                        TextView(this@InstalledAppsActivity).also { details ->
                            details.textSize = 9.5f
                            details.maxLines = 2
                            details.setTextColor(UiPreferences.textSecondary(this@InstalledAppsActivity))
                        }
                    )
                    addView(LinearLayout(this@InstalledAppsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(holder.title, LinearLayout.LayoutParams(-1, -2))
                        addView(holder.details, LinearLayout.LayoutParams(-1, -2))
                    }, LinearLayout.LayoutParams(0, dp(56), 1f))
                    tag = holder
                }
            } else {
                holder = convertView.tag as AppHolder
                convertView as LinearLayout
            }

            val info = getItem(position)
            val appInfo = info.applicationInfo
            val label = runCatching { packageManager.getApplicationLabel(appInfo!!).toString() }.getOrDefault(info.packageName)
            val system = (appInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
            val splitCount = appInfo?.splitSourceDirs?.size ?: 0
            holder.icon.setImageDrawable(runCatching { packageManager.getApplicationIcon(info.packageName) }.getOrNull())
            holder.title.text = label
            holder.details.text = buildString {
                append(info.packageName)
                if (!info.versionName.isNullOrBlank()) append("  ").append(info.versionName)
                append("  •  ").append(if (system) "sistema" else "usuário")
                if (splitCount > 0) append("  •  ").append(splitCount + 1).append(" APKs")
            }
            row.setBackgroundColor(UiPreferences.background(this@InstalledAppsActivity))
            return row
        }
    }

    private data class AppHolder(val icon: ImageView, val title: TextView, val details: TextView)
    private data class PendingExtract(val packageName: String, val baseOnly: Boolean)

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_BROWSE = "browse"
        const val MODE_EXTRACT = "extract"
    }
}
