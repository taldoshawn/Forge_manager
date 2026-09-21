package com.forgemanager.app.features.explorer

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.forgemanager.app.ForgeApplication
import com.forgemanager.app.MainActivity
import com.forgemanager.app.R
import com.forgemanager.app.core.file.FileLocation
import com.forgemanager.app.core.file.putFileLocation
import com.forgemanager.app.features.apktools.ApkToolboxActivity
import com.forgemanager.app.features.apps.InstalledAppsActivity
import com.forgemanager.app.features.browser.ForgeBrowserActivity
import com.forgemanager.app.features.compare.TextCompareActivity
import com.forgemanager.app.features.dex.DexInspectorActivity
import com.forgemanager.app.features.editor.HexViewerActivity
import com.forgemanager.app.features.resources.ApkResourceStudioActivity
import com.forgemanager.app.features.settings.SettingsActivity
import com.forgemanager.app.features.settings.UiPreferences
import com.forgemanager.app.features.system.ActivityRecordActivity
import com.forgemanager.app.features.terminal.TerminalActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale

/**
 * Power-tool drawer attached to the main hamburger button.
 *
 * Everything exposed here is wired to a real implementation. Contextual tools
 * open a small picker rooted in the active pane instead of showing dead/fake
 * actions when no file is selected.
 */
object ForgeSideDrawer {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private enum class Action {
        INTERNAL, DOWNLOADS, ANDROID_DATA, ANDROID_OBB, SYSTEM_ROOT,
        APK_EXTRACT, INSTALLED_APPS, APK_TOOLBOX, XAPK_TOOLBOX,
        DEX_EDITOR, RESOURCE_STUDIO, TERMINAL, WEB, ACTIVITY_RECORD,
        ROOT, SHIZUKU, SAF, ALL_FILES,
        BOOKMARKS, SEARCH, HASH, COMPARE, STORAGE_ANALYZER, DATABASE,
        SETTINGS, HELP
    }

    private data class Tool(
        val section: String,
        val title: String,
        val subtitle: String,
        val iconSlot: Int,
        val action: Action
    )

    private val tools = listOf(
        Tool("ATALHOS", "Armazenamento interno", "/storage/emulated/0", 19, Action.INTERNAL),
        Tool("ATALHOS", "Downloads", "Pasta pública de downloads", 12, Action.DOWNLOADS),
        Tool("ATALHOS", "Android/data", "Acesso direto / SAF / Shizuku / root", 9, Action.ANDROID_DATA),
        Tool("ATALHOS", "Android/obb", "Dados OBB de aplicativos", 9, Action.ANDROID_OBB),
        Tool("ATALHOS", "Raiz do sistema /", "Navegar pelo filesystem do Android", 10, Action.SYSTEM_ROOT),

        Tool("APPS & APK", "Extrair APKs instalados", "APK base, splits/APKS e exportação em lote", 0, Action.APK_EXTRACT),
        Tool("APPS & APK", "Aplicativos instalados", "Abrir, detalhes, exportar e desinstalar", 1, Action.INSTALLED_APPS),
        Tool("APPS & APK", "APK Toolbox", "Extrair, recompilar, zipalign, assinar e verificar", 2, Action.APK_TOOLBOX),
        Tool("APPS & APK", "XAPK / APKS / APKM", "Converter pacote composto para APK e extrair", 3, Action.XAPK_TOOLBOX),
        Tool("APPS & APK", "Resource Studio", "AndroidManifest, AXML, ARSC e recursos", 5, Action.RESOURCE_STUDIO),

        Tool("REVERSE & DEV", "DEX Editor Plus", "DEX/Smali, busca e projeto multi-DEX", 4, Action.DEX_EDITOR),
        Tool("REVERSE & DEV", "Activity Record", "Activity em primeiro plano e histórico via root", 8, Action.ACTIVITY_RECORD),
        Tool("REVERSE & DEV", "Terminal PTY", "Shell no diretório do painel ativo", 6, Action.TERMINAL),
        Tool("REVERSE & DEV", "Banco / Hex", "Abrir DB, SQLite e binários no editor hexadecimal", 20, Action.DATABASE),
        Tool("REVERSE & DEV", "Hashes", "MD5, SHA-1 e SHA-256 de qualquer arquivo", 16, Action.HASH),
        Tool("REVERSE & DEV", "Comparar arquivos", "Comparação textual lado a lado", 17, Action.COMPARE),

        Tool("FERRAMENTAS", "Analisar armazenamento", "Calcula os maiores itens da pasta atual", 19, Action.STORAGE_ANALYZER),
        Tool("FERRAMENTAS", "Pesquisar / filtrar", "Filtro rápido, negação e regex do painel", 15, Action.SEARCH),
        Tool("FERRAMENTAS", "Bookmarks", "Abrir caminhos salvos", 14, Action.BOOKMARKS),
        Tool("FERRAMENTAS", "Forge Web", "Navegador interno HTTPS com downloads", 7, Action.WEB),

        Tool("ACESSO AVANÇADO", "Shizuku", "Autorizar e conectar backend privilegiado", 11, Action.SHIZUKU),
        Tool("ACESSO AVANÇADO", "Root", "Solicitar superusuário somente quando você pedir", 10, Action.ROOT),
        Tool("ACESSO AVANÇADO", "Autorizar pasta (SAF)", "Conceder acesso persistente a uma árvore", 12, Action.SAF),
        Tool("ACESSO AVANÇADO", "Acesso a todos os arquivos", "Abrir permissão MANAGE_EXTERNAL_STORAGE", 13, Action.ALL_FILES),

        Tool("FORGE", "Configurações", "Tema, cores, comportamento e preferências", 22, Action.SETTINGS),
        Tool("FORGE", "Sobre as ferramentas", "O que cada ferramenta faz e quais exigem privilégios", 23, Action.HELP)
    )

    fun show(activity: MainActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val dialog = Dialog(activity)
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiPreferences.surface(activity))
            isClickable = true
            isFocusable = true
        }

        panel.addView(buildHeader(activity, dialog), LinearLayout.LayoutParams(-1, dp(activity, 92)))
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            setBackgroundColor(UiPreferences.background(activity))
        }
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(activity, 6), 0, dp(activity, 26))
        }

        var section: String? = null
        tools.forEach { tool ->
            if (tool.section != section) {
                section = tool.section
                list.addView(sectionLabel(activity, tool.section))
            }
            list.addView(toolRow(activity, tool) {
                dialog.dismiss()
                run(activity, tool.action)
            })
        }
        scroll.addView(list, ViewGroup.LayoutParams(-1, -2))
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val params = window.attributes
            params.gravity = Gravity.START or Gravity.TOP
            params.dimAmount = 0.44f
            params.width = drawerWidth(activity)
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            params.x = 0
            params.y = 0
            window.attributes = params
            window.setLayout(drawerWidth(activity), WindowManager.LayoutParams.MATCH_PARENT)
        }
        dialog.show()
        dialog.window?.setLayout(drawerWidth(activity), WindowManager.LayoutParams.MATCH_PARENT)
    }

    private fun buildHeader(activity: MainActivity, dialog: Dialog) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(activity, 18), dp(activity, 12), dp(activity, 8), dp(activity, 10))
        setBackgroundColor(UiPreferences.elevatedSurface(activity))

        val titleBlock = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        titleBlock.addView(TextView(activity).apply {
            text = "Forge Power Tools"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(UiPreferences.textPrimary(activity))
        })
        titleBlock.addView(TextView(activity).apply {
            text = "Arquivo • APK • DEX • sistema"
            textSize = 11.5f
            setTextColor(UiPreferences.textSecondary(activity))
        })
        addView(titleBlock, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(activity).apply {
            text = "✕"
            textSize = 22f
            gravity = Gravity.CENTER
            contentDescription = "Fechar painel"
            setTextColor(UiPreferences.textPrimary(activity))
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)))
    }

    private fun sectionLabel(activity: MainActivity, value: String) = TextView(activity).apply {
        text = value
        textSize = 10.5f
        letterSpacing = 0.08f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(UiPreferences.accent(activity))
        setPadding(dp(activity, 18), dp(activity, 18), dp(activity, 12), dp(activity, 7))
    }

    private fun toolRow(activity: MainActivity, tool: Tool, action: () -> Unit) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(activity, 12), dp(activity, 5), dp(activity, 10), dp(activity, 5))
        minimumHeight = dp(activity, 64)
        isClickable = true
        isFocusable = true
        setBackgroundColor(UiPreferences.background(activity))

        val icon = ImageView(activity).apply {
            setImageDrawable(SidebarIconAtlas.drawable(activity, tool.iconSlot))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = tool.title
        }
        addView(icon, LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)).apply {
            marginEnd = dp(activity, 11)
        })

        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(activity).apply {
            text = tool.title
            textSize = 14.2f
            maxLines = 1
            setTextColor(UiPreferences.textPrimary(activity))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        texts.addView(TextView(activity).apply {
            text = tool.subtitle
            textSize = 10.5f
            maxLines = 2
            setTextColor(UiPreferences.textSecondary(activity))
        })
        addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        setOnClickListener { action() }
    }

    private fun run(activity: MainActivity, action: Action) {
        when (action) {
            Action.INTERNAL -> reopenAt(activity, Environment.getExternalStorageDirectory().path)
            Action.DOWNLOADS -> reopenAt(activity, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path)
            Action.ANDROID_DATA -> reopenAt(activity, File(Environment.getExternalStorageDirectory(), "Android/data").path)
            Action.ANDROID_OBB -> reopenAt(activity, File(Environment.getExternalStorageDirectory(), "Android/obb").path)
            Action.SYSTEM_ROOT -> reopenAt(activity, "/")

            Action.APK_EXTRACT -> activity.startActivity(Intent(activity, InstalledAppsActivity::class.java)
                .putExtra(InstalledAppsActivity.EXTRA_MODE, InstalledAppsActivity.MODE_EXTRACT))
            Action.INSTALLED_APPS -> activity.startActivity(Intent(activity, InstalledAppsActivity::class.java))
            Action.APK_TOOLBOX -> chooseFile(activity, "Escolha um APK", setOf("apk")) { file ->
                activity.startActivity(Intent(activity, ApkToolboxActivity::class.java).putExtra(ApkToolboxActivity.EXTRA_APK_PATH, file.path))
            }
            Action.XAPK_TOOLBOX -> chooseFile(activity, "Escolha XAPK / APKS / APKM", setOf("xapk", "apks", "apkm", "zip")) { file ->
                activity.startActivity(Intent(activity, ApkToolboxActivity::class.java).putExtra(ApkToolboxActivity.EXTRA_APK_PATH, file.path))
            }
            Action.RESOURCE_STUDIO -> chooseFile(activity, "Resource Studio — APK", setOf("apk")) { file ->
                activity.startActivity(Intent(activity, ApkResourceStudioActivity::class.java)
                    .putExtra(ApkResourceStudioActivity.EXTRA_APK_PATH, file.path))
            }
            Action.DEX_EDITOR -> chooseFile(activity, "DEX Editor Plus", setOf("dex", "vdex", "odex", "apk")) { file ->
                activity.startActivity(Intent(activity, DexInspectorActivity::class.java).putExtra("path", file.path))
            }
            Action.TERMINAL -> {
                val working = currentDirectory(activity).path
                activity.startActivity(Intent(activity, TerminalActivity::class.java)
                    .putExtra(TerminalActivity.EXTRA_WORKING_DIRECTORY, working))
            }
            Action.WEB -> activity.startActivity(Intent(activity, ForgeBrowserActivity::class.java))
            Action.ACTIVITY_RECORD -> activity.startActivity(Intent(activity, ActivityRecordActivity::class.java))

            Action.ROOT -> authorizeRoot(activity)
            Action.SHIZUKU -> requestShizuku(activity)
            Action.SAF -> requestSaf(activity)
            Action.ALL_FILES -> requestAllFiles(activity)

            Action.BOOKMARKS -> activity.findViewById<View>(R.id.backButton)?.performLongClick()
            Action.SEARCH -> activity.findViewById<View>(R.id.syncButton)?.performLongClick()
            Action.HASH -> chooseFile(activity, "Calcular hashes", null) { file -> calculateHashes(activity, file) }
            Action.COMPARE -> chooseCompareFiles(activity)
            Action.STORAGE_ANALYZER -> analyzeStorage(activity)
            Action.DATABASE -> chooseFile(activity, "Banco / Hex", setOf("db", "sqlite", "sqlite3", "realm", "mdb", "accdb")) { file ->
                activity.startActivity(Intent(activity, HexViewerActivity::class.java)
                    .putFileLocation(FileLocation.Direct(file.path), file.name))
            }
            Action.SETTINGS -> activity.startActivity(Intent(activity, SettingsActivity::class.java))
            Action.HELP -> showHelp(activity)
        }
    }

    private fun currentDirectory(activity: MainActivity): File {
        val shown = activity.findViewById<TextView>(R.id.activePath)?.text?.toString().orEmpty()
        val candidate = shown.takeIf { it.isNotBlank() }?.let(::File)
        return candidate?.takeIf { it.isDirectory } ?: Environment.getExternalStorageDirectory()
    }

    private fun chooseFile(
        activity: MainActivity,
        title: String,
        extensions: Set<String>?,
        onPick: (File) -> Unit
    ) {
        val directory = currentDirectory(activity)
        val files = runCatching {
            directory.listFiles().orEmpty().asSequence()
                .filter { it.isFile }
                .filter { extensions == null || it.extension.lowercase(Locale.ROOT) in extensions }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
                .take(300)
                .toList()
        }.getOrElse { emptyList() }
        if (files.isEmpty()) {
            toast(activity, if (extensions == null) "Nenhum arquivo nesta pasta" else "Nenhum arquivo compatível nesta pasta")
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("$title\n${directory.path}")
            .setItems(files.map { it.name }.toTypedArray()) { _, which -> files.getOrNull(which)?.let(onPick) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun chooseCompareFiles(activity: MainActivity) {
        chooseFile(activity, "Comparar — arquivo esquerdo", null) { left ->
            chooseFile(activity, "Comparar — arquivo direito", null) { right ->
                if (left.path == right.path) {
                    toast(activity, "Escolha dois arquivos diferentes")
                    return@chooseFile
                }
                activity.startActivity(Intent(activity, TextCompareActivity::class.java)
                    .putExtra(TextCompareActivity.EXTRA_LEFT, left.path)
                    .putExtra(TextCompareActivity.EXTRA_RIGHT, right.path))
            }
        }
    }

    private fun calculateHashes(activity: MainActivity, file: File) {
        val progress = AlertDialog.Builder(activity)
            .setTitle("Hashes")
            .setMessage("Calculando ${file.name}…")
            .setNegativeButton("Fechar", null)
            .show()
        ioScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                val md5 = MessageDigest.getInstance("MD5")
                val sha1 = MessageDigest.getInstance("SHA-1")
                val sha256 = MessageDigest.getInstance("SHA-256")
                FileInputStream(file).buffered(128 * 1024).use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        md5.update(buffer, 0, count)
                        sha1.update(buffer, 0, count)
                        sha256.update(buffer, 0, count)
                    }
                }
                "Arquivo: ${file.name}\nTamanho: ${formatBytes(file.length())}\n\nMD5\n${hex(md5.digest())}\n\nSHA-1\n${hex(sha1.digest())}\n\nSHA-256\n${hex(sha256.digest())}"
            }}
            if (activity.isFinishing || activity.isDestroyed) return@launch
            progress.dismiss()
            result.onSuccess { text ->
                AlertDialog.Builder(activity).setTitle("Hashes").setMessage(text)
                    .setPositiveButton("OK", null).show()
            }.onFailure { toast(activity, it.message ?: "Falha ao calcular hash") }
        }
    }

    private fun analyzeStorage(activity: MainActivity) {
        val root = currentDirectory(activity)
        val progress = AlertDialog.Builder(activity)
            .setTitle("Analisar armazenamento")
            .setMessage("Medindo ${root.path}…")
            .setNegativeButton("Fechar", null)
            .show()
        ioScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                val budget = ScanBudget(MAX_SCAN_ENTRIES)
                val rows = root.listFiles().orEmpty().map { child ->
                    child to safeSize(child, budget, 0)
                }.sortedByDescending { it.second }
                buildString {
                    append("Pasta: ").append(root.path).append('\n')
                    append("Itens analisados: ").append((MAX_SCAN_ENTRIES - budget.remaining).coerceAtLeast(0)).append('\n')
                    if (budget.remaining <= 0) append("Limite de varredura atingido; valores podem ser parciais.\n")
                    append('\n')
                    rows.take(40).forEachIndexed { index, (file, size) ->
                        append(index + 1).append(". ").append(formatBytes(size)).append("  ").append(file.name).append('\n')
                    }
                }
            }}
            if (activity.isFinishing || activity.isDestroyed) return@launch
            progress.dismiss()
            result.onSuccess { text ->
                AlertDialog.Builder(activity).setTitle("Maiores itens").setMessage(text)
                    .setPositiveButton("OK", null).show()
            }.onFailure { toast(activity, it.message ?: "Falha ao analisar pasta") }
        }
    }

    private fun safeSize(file: File, budget: ScanBudget, depth: Int): Long {
        if (budget.remaining <= 0 || depth > MAX_SCAN_DEPTH) return 0L
        budget.remaining--
        if (runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)) return 0L
        if (file.isFile) return file.length().coerceAtLeast(0)
        if (!file.isDirectory) return 0L
        var total = 0L
        for (child in file.listFiles().orEmpty()) {
            if (budget.remaining <= 0) break
            total = (total + safeSize(child, budget, depth + 1)).coerceAtMost(Long.MAX_VALUE / 2)
        }
        return total
    }

    private fun authorizeRoot(activity: MainActivity) {
        val graph = (activity.application as ForgeApplication).graph
        if (graph.root.isAuthorized()) {
            toast(activity, "Root já autorizado")
            return
        }
        ioScope.launch {
            val ok = runCatching { withContext(Dispatchers.IO) { graph.root.authorize() } }.getOrDefault(false)
            if (!activity.isFinishing && !activity.isDestroyed) toast(activity, if (ok) "Root autorizado" else "Root indisponível ou negado")
        }
    }

    private fun requestShizuku(activity: MainActivity) {
        val bridge = (activity.application as ForgeApplication).graph.shizuku
        runCatching {
            if (bridge.hasPermission()) {
                bridge.bindService()
                toast(activity, bridge.status())
            } else bridge.requestPermission(REQ_SHIZUKU)
        }.onFailure { toast(activity, it.message ?: "Shizuku indisponível") }
    }

    @Suppress("DEPRECATION")
    private fun requestSaf(activity: MainActivity) {
        activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQ_TREE)
    }

    private fun requestAllFiles(activity: MainActivity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            toast(activity, "Esta permissão é específica do Android 11+")
            return
        }
        if (Environment.isExternalStorageManager()) {
            toast(activity, "Acesso a todos os arquivos já autorizado")
            return
        }
        val own = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${activity.packageName}"))
        runCatching { activity.startActivity(own) }
            .onFailure { activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }

    private fun reopenAt(activity: MainActivity, path: String) {
        activity.intent.putExtra(MainActivity.EXTRA_OPEN_PATH, path)
        activity.recreate()
    }

    private fun showHelp(activity: MainActivity) {
        AlertDialog.Builder(activity)
            .setTitle("Forge Power Tools")
            .setMessage(
                "O painel reúne ferramentas de arquivo e engenharia Android em um único lugar.\n\n" +
                    "• Extrator APK lê os APKs realmente instalados e pode salvar base/splits.\n" +
                    "• DEX Editor Plus abre DEX/APK e permite adicionar outros DEX ao projeto.\n" +
                    "• Activity Record usa dumpsys via root; sem root ele não inventa resultados.\n" +
                    "• Shizuku, root e SAF só são solicitados quando você toca na opção.\n" +
                    "• Hashes e análise de armazenamento trabalham localmente no aparelho.\n\n" +
                    "Os ícones deste painel usam automaticamente o pack branco no tema claro/cinza e o pack preto no tema escuro."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun drawerWidth(activity: MainActivity): Int {
        val width = activity.resources.displayMetrics.widthPixels
        return minOf((width * 0.88f).toInt(), dp(activity, 430)).coerceAtLeast(dp(activity, 280))
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun formatBytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var size = value.toDouble()
        var index = -1
        do { size /= 1024.0; index++ } while (size >= 1024 && index < units.lastIndex)
        return String.format(Locale.US, "%.1f %s", size, units[index])
    }

    private fun toast(activity: MainActivity, value: String) = Toast.makeText(activity, value, Toast.LENGTH_SHORT).show()
    private fun dp(activity: MainActivity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private data class ScanBudget(var remaining: Int)

    private const val REQ_TREE = 100
    private const val REQ_SHIZUKU = 101
    private const val MAX_SCAN_ENTRIES = 80_000
    private const val MAX_SCAN_DEPTH = 32
}
