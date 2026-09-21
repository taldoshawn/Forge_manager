package com.forgemanager.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import com.forgemanager.app.core.file.ConflictPolicy
import com.forgemanager.app.core.file.FileAccessException
import com.forgemanager.app.core.file.FileLocation
import com.forgemanager.app.core.file.FileNode
import com.forgemanager.app.core.file.FileOperations
import com.forgemanager.app.core.file.OperationProgress
import com.forgemanager.app.core.file.putFileLocation
import com.forgemanager.app.core.security.PathSecurity
import com.forgemanager.app.archive.ZipOperations
import com.forgemanager.app.features.apk.ApkInspectorActivity
import com.forgemanager.app.features.apktools.ApkToolboxActivity
import com.forgemanager.app.features.apps.InstalledAppsActivity
import com.forgemanager.app.features.browser.ForgeBrowserActivity
import com.forgemanager.app.features.compare.TextCompareActivity
import com.forgemanager.app.features.editor.HexViewerActivity
import com.forgemanager.app.features.editor.TextEditorActivity
import com.forgemanager.app.features.editor.HtmlPreviewActivity
import com.forgemanager.app.features.viewer.ImageViewerActivity
import com.forgemanager.app.features.terminal.TerminalActivity
import com.forgemanager.app.features.dex.DexInspectorActivity
import com.forgemanager.app.features.dex.SmaliStudioActivity
import com.forgemanager.app.features.disk.DiskImageActivity
import com.forgemanager.app.features.resources.BinaryResourceEditorActivity
import com.forgemanager.app.features.resources.ApkResourceStudioActivity
import com.forgemanager.app.features.settings.SettingsActivity
import com.forgemanager.app.features.settings.UiPreferences
import com.forgemanager.app.features.torrent.TorrentInspectorActivity
import com.forgemanager.app.features.explorer.DualPaneController
import com.forgemanager.app.features.explorer.FileKind
import com.forgemanager.app.features.explorer.FileListAdapter
import com.forgemanager.app.features.explorer.FileTypeClassifier
import com.forgemanager.app.features.explorer.PaneId
import com.forgemanager.app.features.explorer.PaneState
import com.forgemanager.app.features.explorer.SortField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }
    private val operations by lazy { FileOperations(graph.resolver) }
    private val zipOperations by lazy { ZipOperations(graph.resolver) }
    private lateinit var controller: DualPaneController
    private lateinit var leftUi: PaneUi
    private lateinit var rightUi: PaneUi
    private lateinit var activePath: TextView
    private lateinit var folderInfo: TextView
    private var operationJob: Job? = null
    private val rangeAnchor = mutableMapOf<PaneId, Int>()

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQ_SHIZUKU && grantResult == PackageManager.PERMISSION_GRANTED) {
            graph.shizuku.bindService()
            toast("Shizuku autorizado")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        installSystemBarInsets()
        val initialPath = intent.getStringExtra(EXTRA_OPEN_PATH)?.takeIf { it.isNotBlank() } ?: Environment.getExternalStorageDirectory().path
        val initial = FileLocation.Direct(initialPath)
        controller = DualPaneController(initial, initial)
        val showHiddenDefault = UiPreferences.showHiddenDefault(this)
        controller.pane(PaneId.LEFT).showHidden = showHiddenDefault
        controller.pane(PaneId.RIGHT).showHidden = showHiddenDefault
        activePath = findViewById(R.id.activePath)
        folderInfo = findViewById(R.id.folderInfo)
        leftUi = bindPane(PaneId.LEFT, findViewById(R.id.leftPane))
        rightUi = bindPane(PaneId.RIGHT, findViewById(R.id.rightPane))
        bindToolbar()
        applyUiPreferences()
        activate(PaneId.LEFT)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        requestInitialStorageAccess()
        refreshBoth()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        operationJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::controller.isInitialized) {
            applyUiPreferences()
            leftUi.adapter.notifyDataSetChanged()
            rightUi.adapter.notifyDataSetChanged()
            refresh(controller.activePane)
        }
    }

    @Deprecated("Android back compatibility")
    override fun onBackPressed() {
        handleBackNavigation()
    }

    @Deprecated("SAF compatibility")
    @SuppressLint("WrongConstant")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_TREE && resultCode == RESULT_OK) {
            val resultIntent = data ?: return
            val uri = resultIntent.data ?: return
            val flags = resultIntent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
                .onFailure { toast("O provedor não permitiu persistir a autorização") }
            navigate(controller.activePane, com.forgemanager.app.core.file.SafFileBackend.fromTreeUri(uri))
        }
    }

    private fun installSystemBarInsets() {
        val root = findViewById<View>(R.id.root)
        val topBar = findViewById<View>(R.id.topBar)
        val bottomBar = findViewById<View>(R.id.bottomBar)
        val density = resources.displayMetrics.density
        val topBase = (62 * density).toInt()
        val bottomBase = (54 * density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topBar.setPadding(topBar.paddingLeft, bars.top, topBar.paddingRight, topBar.paddingBottom)
            topBar.layoutParams = topBar.layoutParams.apply { height = topBase + bars.top }
            bottomBar.setPadding(bottomBar.paddingLeft, bottomBar.paddingTop, bottomBar.paddingRight, bars.bottom)
            bottomBar.layoutParams = bottomBar.layoutParams.apply { height = bottomBase + bars.bottom }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun bindPane(id: PaneId, root: View): PaneUi {
        val header = root.findViewById<View>(R.id.paneHeader)
        val path = root.findViewById<TextView>(R.id.panePath)
        val list = root.findViewById<ListView>(R.id.fileList)
        val adapter = FileListAdapter(this) { node -> node.location.displayPath in controller.pane(id).selection }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            activate(id)
            val node = adapter.getItem(position)
            if (controller.pane(id).selection.isNotEmpty()) {
                controller.toggleSelection(node)
                adapter.notifyDataSetChanged()
                updateHeader()
            } else openNode(id, node)
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            activate(id)
            val node = adapter.getItem(position)
            if (node.location.displayPath !in controller.pane(id).selection) controller.toggleSelection(node)
            adapter.notifyDataSetChanged()
            showSelectionActions(node)
            true
        }
        installSwipeSelection(id, list, adapter)
        list.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: android.widget.AbsListView?, scrollState: Int) = Unit
            override fun onScroll(view: android.widget.AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                controller.pane(id).scrollPosition = firstVisibleItem
            }
        })
        header.setOnClickListener { activate(id) }
        root.setOnClickListener { activate(id) }
        return PaneUi(root, header, path, list, adapter)
    }

    private fun installSwipeSelection(id: PaneId, list: ListView, adapter: FileListAdapter) {
        var downX = 0f
        var downY = 0f
        list.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y }
                MotionEvent.ACTION_UP -> {
                    if (kotlin.math.abs(event.x - downX) > 52 * resources.displayMetrics.density &&
                        kotlin.math.abs(event.y - downY) < 36 * resources.displayMetrics.density) {
                        val position = list.pointToPosition(downX.toInt(), downY.toInt())
                        if (position >= 0) {
                            activate(id)
                            val anchor = rangeAnchor[id]
                            if (anchor != null && controller.pane(id).selection.isNotEmpty()) {
                                val range = if (anchor <= position) anchor..position else position..anchor
                                range.forEach { controller.pane(id).selection += adapter.getItem(it).location.displayPath }
                                rangeAnchor.remove(id)
                            } else {
                                controller.toggleSelection(adapter.getItem(position))
                                rangeAnchor[id] = position
                            }
                            adapter.notifyDataSetChanged()
                            updateHeader()
                            return@setOnTouchListener true
                        }
                    }
                }
            }
            false
        }
    }

    private fun bindToolbar() {
        findViewById<View>(R.id.menuButton).setOnClickListener { showMainMenu(it) }
        findViewById<View>(R.id.moreButton).setOnClickListener { showMoreMenu(it) }
        findViewById<View>(R.id.backButton).setOnClickListener { handleBackNavigation() }
        findViewById<View>(R.id.backButton).setOnLongClickListener { showBookmarks(); true }
        findViewById<View>(R.id.forwardButton).setOnClickListener { controller.forward()?.let { refresh(controller.activePane) } }
        findViewById<View>(R.id.createButton).setOnClickListener { showCreateDialog() }
        findViewById<View>(R.id.syncButton).setOnClickListener { controller.syncFromActive(); refresh(if (controller.activePane == PaneId.LEFT) PaneId.RIGHT else PaneId.LEFT) }
        findViewById<View>(R.id.syncButton).setOnLongClickListener { showFilterDialog(); true }
        findViewById<View>(R.id.upButton).setOnClickListener {
            scope.launch {
                runCatching { graph.resolver.backendFor(controller.pane().current).parent(controller.pane().current) }
                    .onSuccess { it?.let { navigate(controller.activePane, it) } }
                    .onFailure(::showError)
            }
        }
        findViewById<View>(R.id.upButton).setOnLongClickListener { showPathJump(); true }
    }

    private fun refreshBoth() { refresh(PaneId.LEFT); refresh(PaneId.RIGHT) }

    private fun refresh(id: PaneId) {
        val state = controller.pane(id)
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { graph.resolver.backendFor(state.current).list(state.current, state.showHidden) }
            }
            result.onSuccess { raw ->
                state.items = sortAndFilter(raw, state)
                val ui = ui(id)
                ui.path.text = state.current.displayPath
                ui.adapter.submitList(state.items)
                ui.list.setSelection(state.scrollPosition.coerceAtMost((state.items.size - 1).coerceAtLeast(0)))
                if (controller.activePane == id) updateHeader()
            }.onFailure(::showError)
        }
    }

    private fun sortAndFilter(items: List<FileNode>, state: PaneState): List<FileNode> {
        val filtered = if (state.filter.isBlank()) items else {
            val f = state.filter
            val negate = f.startsWith('!')
            val body = if (negate) f.drop(1) else f
            val matcher: (String) -> Boolean = if (body.startsWith('/')) {
                val regex = runCatching { Regex(body.drop(1), RegexOption.IGNORE_CASE) }.getOrNull() ?: return emptyList()
                val regexMatcher: (String) -> Boolean = { name -> regex.containsMatchIn(name) }
                regexMatcher
            } else { name -> name.contains(body, ignoreCase = true) }
            items.filter { matcher(it.name) xor negate }
        }
        val comparator = when (state.sortField) {
            SortField.NAME -> compareBy<FileNode> { it.name.lowercase(Locale.ROOT) }
            SortField.SIZE -> compareBy { it.size }
            SortField.DATE -> compareBy { it.modified }
            SortField.TYPE -> compareBy { it.name.substringAfterLast('.', "").lowercase(Locale.ROOT) }
        }
        val ordered = filtered.sortedWith(compareBy<FileNode> { !it.isDirectory }.then(if (state.ascending) comparator else comparator.reversed()))
        return ordered
    }

    private fun navigate(id: PaneId, destination: FileLocation) {
        controller.navigate(id, destination)
        refresh(id)
    }

    private fun activate(id: PaneId) {
        controller.activate(id)
        leftUi.header.setBackgroundResource(if (id == PaneId.LEFT) R.drawable.bg_pane_header_active else R.drawable.bg_pane_header)
        rightUi.header.setBackgroundResource(if (id == PaneId.RIGHT) R.drawable.bg_pane_header_active else R.drawable.bg_pane_header)
        applyPaneAccent(id)
        updateHeader()
    }

    private fun applyUiPreferences() {
        val surface = UiPreferences.surface(this)
        val elevated = UiPreferences.elevatedSurface(this)
        val accent = UiPreferences.accent(this)
        window.statusBarColor = surface
        window.navigationBarColor = surface
        findViewById<View>(R.id.root).setBackgroundColor(surface)
        findViewById<View>(R.id.topBar).backgroundTintList = android.content.res.ColorStateList.valueOf(elevated)
        findViewById<View>(R.id.bottomBar).backgroundTintList = android.content.res.ColorStateList.valueOf(elevated)
        if (::leftUi.isInitialized) {
            leftUi.root.setBackgroundColor(surface)
            rightUi.root.setBackgroundColor(surface)
            leftUi.list.setBackgroundColor(surface)
            rightUi.list.setBackgroundColor(surface)
            applyPaneAccent(controller.activePane)
        }
        findViewById<android.widget.ImageButton>(R.id.createButton).imageTintList = android.content.res.ColorStateList.valueOf(accent)
        if (::folderInfo.isInitialized) folderInfo.setTextColor(accent)
    }

    private fun applyPaneAccent(id: PaneId) {
        if (!::leftUi.isInitialized) return
        val accent = UiPreferences.accent(this)
        val elevated = UiPreferences.elevatedSurface(this)
        val active = android.graphics.Color.rgb(
            (android.graphics.Color.red(accent) * 0.22f).toInt().coerceIn(0, 255),
            (android.graphics.Color.green(accent) * 0.22f).toInt().coerceIn(0, 255),
            (android.graphics.Color.blue(accent) * 0.22f).toInt().coerceIn(0, 255)
        )
        leftUi.header.backgroundTintList = android.content.res.ColorStateList.valueOf(if (id == PaneId.LEFT) active else elevated)
        rightUi.header.backgroundTintList = android.content.res.ColorStateList.valueOf(if (id == PaneId.RIGHT) active else elevated)
        leftUi.path.setTextColor(if (id == PaneId.LEFT) accent else android.graphics.Color.rgb(132, 143, 158))
        rightUi.path.setTextColor(if (id == PaneId.RIGHT) accent else android.graphics.Color.rgb(132, 143, 158))
    }

    private fun updateHeader() {
        val state = controller.pane()
        activePath.text = state.current.displayPath
        val dirs = state.items.count { it.isDirectory }
        val files = state.items.size - dirs
        folderInfo.text = if (state.selection.isEmpty()) "Pastas: $dirs  Arquivos: $files" else "${state.selection.size} selecionado(s)"
    }

    private fun openNode(id: PaneId, node: FileNode) {
        if (node.isDirectory) { navigate(id, node.location); return }
        val extension = FileTypeClassifier.extensionOf(node.name)
        val kind = FileTypeClassifier.classify(node.name, false)
        val zipContainers = setOf("zip", "jar", "apk", "aar", "apks", "xapk", "apkm", "aab", "epub")

        if (node.location is FileLocation.Direct && extension in zipContainers) {
            val archivePath = node.location.path
            if (extension == "apk") {
                AlertDialog.Builder(this).setTitle(node.name)
                    .setItems(arrayOf("Abrir como ZIP", "Resource Studio", "APK Toolbox / Assinar", "Informações do APK", "DEX Inspector", "Abrir com…")) { _, which ->
                        when (which) {
                            0 -> navigate(id, FileLocation.Archive(archivePath))
                            1 -> startActivity(Intent(this, ApkResourceStudioActivity::class.java).putExtra(ApkResourceStudioActivity.EXTRA_APK_PATH, archivePath))
                            2 -> startActivity(Intent(this, ApkToolboxActivity::class.java).putExtra(ApkToolboxActivity.EXTRA_APK_PATH, archivePath))
                            3 -> startActivity(Intent(this, ApkInspectorActivity::class.java).putExtra("path", archivePath))
                            4 -> startActivity(Intent(this, DexInspectorActivity::class.java).putExtra("path", archivePath))
                            5 -> openWith(node)
                        }
                    }.show()
            } else if (UiPreferences.openArchivesInternally(this)) {
                navigate(id, FileLocation.Archive(archivePath))
            } else {
                openWith(node)
            }
            return
        }

        when (kind) {
            FileKind.IMAGE -> startActivity(Intent(this, ImageViewerActivity::class.java).putFileLocation(node.location, node.name))
            FileKind.CODE, FileKind.SCRIPT, FileKind.MARKDOWN, FileKind.TEXT, FileKind.CONFIG -> openTextEditor(node)
            FileKind.XML -> {
                val binaryAxml = node.location is FileLocation.Archive && (node.location as FileLocation.Archive).archivePath.endsWith(".apk", true)
                if (binaryAxml) startActivity(Intent(this, BinaryResourceEditorActivity::class.java).putFileLocation(node.location, node.name)) else openTextEditor(node)
            }
            FileKind.WEB -> {
                if (extension in setOf("html", "htm", "xhtml")) {
                    AlertDialog.Builder(this).setTitle(node.name)
                        .setItems(arrayOf("Visualizar página", "Editar código", "Abrir com…")) { _, which ->
                            when (which) {
                                0 -> startActivity(Intent(this, HtmlPreviewActivity::class.java).putFileLocation(node.location, node.name))
                                1 -> openTextEditor(node)
                                2 -> openWith(node)
                            }
                        }.show()
                } else openTextEditor(node)
            }
            FileKind.DEX -> {
                val direct = node.location as? FileLocation.Direct
                val options = if (direct != null) arrayOf("Smali/DEX Studio", "DEX Inspector", "Hexadecimal") else arrayOf("Smali/DEX Studio", "Hexadecimal")
                AlertDialog.Builder(this).setTitle(node.name).setItems(options) { _, which ->
                    when {
                        which == 0 -> startActivity(Intent(this, SmaliStudioActivity::class.java).putFileLocation(node.location, node.name))
                        direct != null && which == 1 -> startActivity(Intent(this, DexInspectorActivity::class.java).putExtra("path", direct.path))
                        else -> openHexEditor(node)
                    }
                }.show()
            }
            FileKind.BINARY_RESOURCE -> startActivity(Intent(this, BinaryResourceEditorActivity::class.java).putFileLocation(node.location, node.name))
            FileKind.DISK_IMAGE -> startActivity(Intent(this, DiskImageActivity::class.java).putFileLocation(node.location, node.name))
            FileKind.SUBTITLE -> openTextEditor(node)
            FileKind.TORRENT -> startActivity(Intent(this, TorrentInspectorActivity::class.java).putFileLocation(node.location, node.name))
            FileKind.KEY, FileKind.CERTIFICATE, FileKind.BACKUP -> showTextHexOpenDialog(node)
            FileKind.VIDEO, FileKind.AUDIO, FileKind.PDF, FileKind.DOCUMENT, FileKind.SPREADSHEET,
            FileKind.PRESENTATION, FileKind.FONT, FileKind.LINUX_PACKAGE, FileKind.MODEL3D -> openWith(node)
            FileKind.EXECUTABLE, FileKind.DATABASE -> openHexEditor(node)
            FileKind.APK, FileKind.ARCHIVE -> openWith(node)
            FileKind.GENERIC -> {
                AlertDialog.Builder(this).setTitle(node.name)
                    .setItems(arrayOf("Editar como texto", "Editor hexadecimal", "Abrir com…")) { _, which ->
                        when (which) {
                            0 -> openTextEditor(node)
                            1 -> openHexEditor(node)
                            2 -> openWith(node)
                        }
                    }.show()
            }
            FileKind.DIRECTORY -> Unit
        }
    }

    private fun showSelectionActions(reference: FileNode) {
        val state = controller.pane()
        val items = state.selectedItems().ifEmpty { listOf(reference) }
        val labels = mutableListOf(
            "Copiar → outro painel",
            "Mover → outro painel",
            "Duplicar aqui",
            "Compactar ZIP → outro painel",
            "Excluir",
            "Informações",
            "Hashes",
            "Copiar nome",
            "Copiar caminho",
            "Compartilhar",
            "Abrir com",
            "Visualizar",
            "Editar como texto/código",
            "Editor hexadecimal",
            "Selecionar todos",
            "Inverter seleção",
            "Selecionar mesmo tipo",
            "Cancelar seleção"
        )
        if (items.size == 1) {
            labels.add(2, "Renomear")
            if (items.single().location is FileLocation.Direct && graph.root.isAuthorized()) {
                labels.add(3, "Permissões (chmod)")
            }
        }
        if (items.size == 2) {
            labels.add(4, "Trocar nomes")
            labels.add(5, "Comparar texto")
        }
        AlertDialog.Builder(this).setTitle("${items.size} selecionado(s)").setItems(labels.toTypedArray()) { _, index ->
            when (labels[index]) {
                "Copiar → outro painel" -> chooseConflictPolicy { runTransfer(false, it) }
                "Mover → outro painel" -> chooseConflictPolicy { runTransfer(true, it) }
                "Renomear" -> showRename(items.single())
                "Permissões (chmod)" -> showChmod(items.single())
                "Duplicar aqui" -> duplicateHere(items)
                "Trocar nomes" -> swapNames(items)
                "Comparar texto" -> compareText(items)
                "Compactar ZIP → outro painel" -> showCompressDialog(items)
                "Excluir" -> confirmDelete(items)
                "Informações" -> showInfo(items)
                "Hashes" -> showHashChooser(items)
                "Copiar nome" -> copyToClipboard("Nomes", items.joinToString("\n") { it.name })
                "Copiar caminho" -> copyToClipboard("Caminhos", items.joinToString("\n") { it.location.displayPath })
                "Compartilhar" -> share(items)
                "Abrir com" -> openWith(items.singleOrNull())
                "Visualizar" -> items.singleOrNull()?.let(::openInternalViewer) ?: toast("Selecione um arquivo")
                "Editar como texto/código" -> items.singleOrNull()?.let(::openTextEditor) ?: toast("Selecione um arquivo")
                "Editor hexadecimal" -> items.singleOrNull()?.let(::openHexEditor) ?: toast("Selecione um arquivo")
                "Selecionar todos" -> { controller.selectAll(); ui(controller.activePane).adapter.notifyDataSetChanged(); updateHeader() }
                "Inverter seleção" -> { controller.invertSelection(); ui(controller.activePane).adapter.notifyDataSetChanged(); updateHeader() }
                "Selecionar mesmo tipo" -> { controller.selectSameType(reference); ui(controller.activePane).adapter.notifyDataSetChanged(); updateHeader() }
                "Cancelar seleção" -> { state.selection.clear(); ui(controller.activePane).adapter.notifyDataSetChanged(); updateHeader() }
            }
        }.show()
    }

    private fun openTextEditor(node: FileNode) {
        startActivity(Intent(this, TextEditorActivity::class.java).putFileLocation(node.location, node.name))
    }

    private fun openHexEditor(node: FileNode) {
        startActivity(Intent(this, HexViewerActivity::class.java).putFileLocation(node.location, node.name))
    }

    private fun openInternalViewer(node: FileNode) {
        when (FileTypeClassifier.classify(node.name, node.isDirectory)) {
            FileKind.IMAGE -> startActivity(Intent(this, ImageViewerActivity::class.java).putFileLocation(node.location, node.name))
            FileKind.WEB -> {
                val ext = FileTypeClassifier.extensionOf(node.name)
                if (ext in setOf("html", "htm", "xhtml")) startActivity(Intent(this, HtmlPreviewActivity::class.java).putFileLocation(node.location, node.name))
                else openTextEditor(node)
            }
            FileKind.CODE, FileKind.SCRIPT, FileKind.MARKDOWN, FileKind.TEXT, FileKind.CONFIG -> openTextEditor(node)
            FileKind.XML -> openTextEditor(node)
            FileKind.DEX -> startActivity(Intent(this, SmaliStudioActivity::class.java).putFileLocation(node.location, node.name))
            FileKind.BINARY_RESOURCE -> startActivity(Intent(this, BinaryResourceEditorActivity::class.java).putFileLocation(node.location, node.name))
            FileKind.DISK_IMAGE -> startActivity(Intent(this, DiskImageActivity::class.java).putFileLocation(node.location, node.name))
            FileKind.DATABASE, FileKind.EXECUTABLE -> openHexEditor(node)
            else -> openWith(node)
        }
    }

    private fun showTextHexOpenDialog(node: FileNode) {
        AlertDialog.Builder(this).setTitle(node.name)
            .setItems(arrayOf("Visualizar/editar como texto", "Editor hexadecimal", "Abrir com…")) { _, which ->
                when (which) {
                    0 -> openTextEditor(node)
                    1 -> openHexEditor(node)
                    2 -> openWith(node)
                }
            }.show()
    }

    private fun handleBackNavigation() {
        if (!::controller.isInitialized) return
        val id = controller.activePane
        val state = controller.pane(id)
        if (state.selection.isNotEmpty()) {
            state.selection.clear()
            ui(id).adapter.notifyDataSetChanged()
            updateHeader()
            return
        }
        if (controller.back(id) != null) {
            refresh(id)
            return
        }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { graph.resolver.backendFor(state.current).parent(state.current) } }
                .onSuccess { parent ->
                    if (parent != null) {
                        controller.navigate(id, parent, recordHistory = false)
                        refresh(id)
                    } else toast("Você já está na raiz")
                }
                .onFailure(::showError)
        }
    }

    private fun compareText(items: List<FileNode>) {
        if (items.size != 2) return
        val files = items.mapNotNull { (it.location as? FileLocation.Direct)?.path?.let(::File) }
        if (files.size != 2 || files.any { !it.isFile }) {
            toast("O comparador de texto exige dois arquivos locais")
            return
        }
        startActivity(Intent(this, TextCompareActivity::class.java)
            .putExtra(TextCompareActivity.EXTRA_LEFT, files[0].path)
            .putExtra(TextCompareActivity.EXTRA_RIGHT, files[1].path))
    }

    private fun chooseConflictPolicy(action: (ConflictPolicy) -> Unit) {
        val options = arrayOf("Renomear automaticamente", "Substituir", "Ignorar", "Cancelar")
        AlertDialog.Builder(this).setTitle("Se o destino já existir").setItems(options) { _, which ->
            when (which) { 0 -> action(ConflictPolicy.RENAME); 1 -> action(ConflictPolicy.REPLACE); 2 -> action(ConflictPolicy.SKIP) }
        }.show()
    }

    private fun runTransfer(move: Boolean, policy: ConflictPolicy) {
        val sourceId = controller.activePane
        val sources = controller.pane().selectedItems()
        if (sources.isEmpty()) return
        val destination = controller.otherPane().current
        val dialog = ProgressDialog(this).apply {
            setTitle(if (move) "Movendo" else "Copiando")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 1000
            setCancelable(true)
            setButton(ProgressDialog.BUTTON_NEGATIVE, "Cancelar") { _, _ -> operationJob?.cancel() }
            show()
        }
        operationJob = scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val listener = com.forgemanager.app.core.file.ProgressListener { p: OperationProgress ->
                        runOnUiThread {
                            dialog.setMessage(p.currentName)
                            dialog.progress = if (p.bytesTotal > 0) ((p.bytesDone * 1000 / p.bytesTotal).coerceIn(0, 1000)).toInt()
                            else (p.itemsDone * 1000 / p.itemsTotal.coerceAtLeast(1))
                        }
                    }
                    if (move) operations.move(sources, destination, policy, listener) else operations.copy(sources, destination, policy, listener)
                }
            }.onSuccess {
                controller.pane(sourceId).selection.clear()
                refreshBoth()
            }.onFailure { if (it !is kotlinx.coroutines.CancellationException) showError(it) }
            dialog.dismiss()
        }
    }

    private fun duplicateHere(items: List<FileNode>) {
        val paneId = controller.activePane
        val destination = controller.pane().current
        operationJob = scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { operations.copy(items, destination, ConflictPolicy.RENAME) } }
            result.onSuccess {
                controller.pane(paneId).selection.clear()
                refresh(paneId)
                toast("Cópia criada")
            }.onFailure { if (it !is kotlinx.coroutines.CancellationException) showError(it) }
        }
    }

    private fun swapNames(items: List<FileNode>) {
        if (items.size != 2) return
        val paneId = controller.activePane
        val parent = controller.pane().current
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { operations.swapNames(items[0], items[1], parent) } }
                .onSuccess { controller.pane(paneId).selection.clear(); refresh(paneId) }
                .onFailure(::showError)
        }
    }

    private fun showCompressDialog(items: List<FileNode>) {
        val defaultName = if (items.size == 1) {
            val base = items.single().name.substringBeforeLast('.', items.single().name).ifBlank { "arquivo" }
            "$base.zip"
        } else "arquivo.zip"
        val input = EditText(this).apply { setText(defaultName); setSelection(text.length); setSingleLine() }
        AlertDialog.Builder(this).setTitle("Compactar em ZIP → outro painel").setView(input)
            .setPositiveButton("Continuar") { _, _ ->
                val name = input.text.toString().trim()
                chooseConflictPolicy { policy -> runCompress(items, name, policy) }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun runCompress(items: List<FileNode>, name: String, policy: ConflictPolicy) {
        val sourcePane = controller.activePane
        val destinationPane = if (sourcePane == PaneId.LEFT) PaneId.RIGHT else PaneId.LEFT
        val destination = controller.pane(destinationPane).current
        val dialog = ProgressDialog(this).apply {
            setTitle("Compactando")
            setProgressStyle(ProgressDialog.STYLE_SPINNER)
            isIndeterminate = true
            setCancelable(true)
            setButton(ProgressDialog.BUTTON_NEGATIVE, "Cancelar") { _, _ -> operationJob?.cancel() }
            show()
        }
        operationJob = scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    zipOperations.createZip(items, destination, name, policy) { current ->
                        runOnUiThread { dialog.setMessage(current) }
                    }
                }
            }
            result.onSuccess { created ->
                if (created == null) toast("Arquivo existente ignorado") else toast("ZIP criado: ${created.name}")
                controller.pane(sourcePane).selection.clear()
                refresh(sourcePane)
                refresh(destinationPane)
            }.onFailure { if (it !is kotlinx.coroutines.CancellationException) showError(it) }
            dialog.dismiss()
        }
    }

    private fun showCreateDialog() {
        AlertDialog.Builder(this).setTitle("Criar")
            .setItems(arrayOf("Arquivo", "Pasta")) { _, which -> promptCreate(isDirectory = which == 1) }
            .show()
    }

    private fun promptCreate(isDirectory: Boolean) {
        val input = EditText(this).apply { hint = if (isDirectory) "Nome da pasta" else "Nome do arquivo"; setSingleLine() }
        AlertDialog.Builder(this).setTitle(if (isDirectory) "Nova pasta" else "Novo arquivo").setView(input)
            .setPositiveButton("Criar") { _, _ ->
                val name = input.text.toString()
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) {
                        val current = controller.pane().current
                        val backend = graph.resolver.backendFor(current, write = true)
                        if (isDirectory) backend.mkdir(current, name) else backend.create(current, name)
                    }}.onSuccess { refresh(controller.activePane) }.onFailure(::showError)
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun showRename(node: FileNode) {
        val input = EditText(this).apply { setText(node.name); setSelection(text.length) }
        AlertDialog.Builder(this).setTitle("Renomear").setView(input).setPositiveButton("Salvar") { _, _ ->
            scope.launch { runCatching { withContext(Dispatchers.IO) {
                graph.resolver.backendFor(node.location, write = true).rename(node.location, input.text.toString())
            }}.onSuccess { refresh(controller.activePane) }.onFailure(::showError) }
        }.setNegativeButton("Cancelar", null).show()
    }

    private fun showChmod(node: FileNode) {
        if (node.location !is FileLocation.Direct || !graph.root.isAuthorized()) {
            toast("Autorize root para alterar permissões")
            return
        }
        val currentMode = node.permissions?.substringAfter('(')?.substringBefore(')')?.takeIf { it.matches(Regex("^[0-7]{3,4}$")) }.orEmpty()
        val input = EditText(this).apply {
            setText(currentMode)
            setSelection(text.length)
            hint = "644 ou 0755"
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this).setTitle("Permissões Unix (chmod)").setView(input)
            .setMessage("Use somente modo octal. A alteração é executada via root já autorizado.")
            .setPositiveButton("Aplicar") { _, _ ->
                val mode = input.text.toString().trim()
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { graph.root.chmod(node.location, mode) } }
                        .onSuccess { refresh(controller.activePane); toast("Permissões atualizadas") }
                        .onFailure(::showError)
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun confirmDelete(items: List<FileNode>) {
        if (!UiPreferences.confirmDelete(this)) {
            deleteItems(items)
            return
        }
        AlertDialog.Builder(this).setTitle("Excluir ${items.size} item(ns)?")
            .setMessage("A exclusão é permanente e não pode ser desfeita.")
            .setPositiveButton("Excluir") { _, _ -> deleteItems(items) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun deleteItems(items: List<FileNode>) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { items.forEach { graph.resolver.backendFor(it.location, write = true).delete(it.location) } } }
                .onSuccess { controller.pane().selection.clear(); refresh(controller.activePane) }
                .onFailure(::showError)
        }
    }

    private fun showInfo(items: List<FileNode>) {
        val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
        val text = items.take(20).joinToString("\n\n") { item ->
            val kind = FileTypeClassifier.classify(item.name, item.isDirectory)
            buildString {
                append(item.name).append('\n')
                append(item.location.displayPath).append('\n')
                append(FileTypeClassifier.shortLabel(kind, item.name))
                if (!item.isDirectory) append("  •  ").append(FileListAdapter.formatBytes(item.size))
                if (item.modified > 0) append("\nModificado: ").append(formatter.format(Date(item.modified)))
                item.mimeType?.takeIf { it.isNotBlank() }?.let { append("\nMIME: ").append(it) }
                item.permissions?.takeIf { it.isNotBlank() }?.let { append("\nPermissões: ").append(it) }
                if (item.isSymlink) append("\nLink simbólico")
            }
        }
        AlertDialog.Builder(this).setTitle("Informações").setMessage(text + if (items.size > 20) "\n…" else "").setPositiveButton("OK", null).show()
    }

    private fun showHashChooser(items: List<FileNode>) {
        if (items.none { !it.isDirectory }) { toast("Selecione pelo menos um arquivo"); return }
        val options = arrayOf("MD5", "SHA-1", "SHA-256", "CRC32")
        AlertDialog.Builder(this).setTitle("Calcular hash").setItems(options) { _, which ->
            calculateHash(items, options[which])
        }.show()
    }

    private fun calculateHash(items: List<FileNode>, algorithm: String) {
        val files = items.filterNot { it.isDirectory }
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                val lines = ArrayList<String>(files.size)
                for (node in files) {
                    val value = if (algorithm == "CRC32") {
                        val crc = CRC32()
                        graph.resolver.backendFor(node.location).openInput(node.location).use { input ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                crc.update(buffer, 0, n)
                            }
                        }
                        "%08x".format(Locale.US, crc.value)
                    } else {
                        val digest = MessageDigest.getInstance(algorithm)
                        graph.resolver.backendFor(node.location).openInput(node.location).use { input ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                digest.update(buffer, 0, n)
                            }
                        }
                        digest.digest().joinToString("") { "%02x".format(it) }
                    }
                    lines += "$value  ${node.name}"
                }
                lines.joinToString("\n")
            } }
            result.onSuccess { value ->
                AlertDialog.Builder(this@MainActivity).setTitle(algorithm).setMessage(value)
                    .setPositiveButton("Copiar") { _, _ -> copyToClipboard(algorithm, value) }
                    .setNegativeButton("Fechar", null).show()
            }.onFailure(::showError)
        }
    }

    private fun share(items: List<FileNode>) {
        val files = items.mapNotNull { (it.location as? FileLocation.Direct)?.path?.let(::File) }.filter { it.isFile }
        if (files.size != items.size) { toast("Compartilhamento direto exige arquivos locais comuns"); return }
        val uris = ArrayList(files.map { FileProvider.getUriForFile(this, "$packageName.files", it) })
        val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        startActivity(Intent.createChooser(intent.setType("application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Compartilhar"))
    }

    private fun openWith(item: FileNode?) {
        if (item == null || item.isDirectory) { toast("Selecione exatamente um arquivo"); return }
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { materializeForExternalOpen(item) } }
            result.onSuccess { file ->
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
                val mime = item.mimeType ?: java.net.URLConnection.guessContentTypeFromName(item.name) ?: "application/octet-stream"
                runCatching {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Abrir com"))
                }.onFailure { toast("Nenhum aplicativo compatível") }
            }.onFailure(::showError)
        }
    }

    private suspend fun materializeForExternalOpen(item: FileNode): File {
        val direct = (item.location as? FileLocation.Direct)?.path?.let(::File)
        if (direct != null && direct.isFile && direct.canRead()) return direct
        val backend = graph.resolver.backendFor(item.location)
        val node = backend.stat(item.location)
        if (node.size > 512L * 1024 * 1024) throw FileAccessException("Arquivo grande demais para abrir por cópia temporária")
        val dir = File(cacheDir, "opened-files").apply { mkdirs() }
        val safeName = item.name.replace(Regex("[^A-Za-z0-9._() -]"), "_").take(160).ifBlank { "arquivo.bin" }
        val target = File(dir, "${System.nanoTime()}-$safeName")
        backend.openInput(item.location).use { input ->
            target.outputStream().buffered(128 * 1024).use { output -> input.copyTo(output, 128 * 1024) }
        }
        return target
    }

    private fun showMainMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Armazenamento interno")
            menu.add("Downloads")
            menu.add("Android/data")
            menu.add("Android/obb")
            menu.add("Raiz do sistema (/)")
            menu.add("Autorizar pasta (SAF)")
            menu.add("Acesso a todos os arquivos")
            menu.add("Aplicativos instalados")
            menu.add("Forge Web")
            menu.add("Terminal PTY")
            menu.add("Configurações")
            menu.add("Adicionar bookmark")
            menu.add("Bookmarks")
            menu.add(graph.shizuku.status())
            menu.add(if (graph.root.isAuthorized()) "Root autorizado" else "Autorizar root")
            setOnMenuItemClickListener {
                when {
                    it.title == "Armazenamento interno" -> navigate(controller.activePane, FileLocation.Direct(Environment.getExternalStorageDirectory().path))
                    it.title == "Downloads" -> navigate(controller.activePane, FileLocation.Direct(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path))
                    it.title == "Android/data" -> navigate(controller.activePane, FileLocation.Direct(PathSecurity.androidDataBypass()))
                    it.title == "Android/obb" -> navigate(controller.activePane, FileLocation.Direct(PathSecurity.androidObbBypass()))
                    it.title == "Raiz do sistema (/)" -> navigate(controller.activePane, FileLocation.Direct("/"))
                    it.title == "Autorizar pasta (SAF)" -> requestSafTree()
                    it.title == "Acesso a todos os arquivos" -> requestAllFilesAccess()
                    it.title == "Aplicativos instalados" -> startActivity(Intent(this@MainActivity, InstalledAppsActivity::class.java))
                    it.title == "Forge Web" -> startActivity(Intent(this@MainActivity, ForgeBrowserActivity::class.java))
                    it.title == "Terminal PTY" -> {
                        val working = (controller.pane().current as? FileLocation.Direct)?.path ?: Environment.getExternalStorageDirectory().path
                        startActivity(Intent(this@MainActivity, TerminalActivity::class.java).putExtra(TerminalActivity.EXTRA_WORKING_DIRECTORY, working))
                    }
                    it.title == "Configurações" -> startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    it.title == "Adicionar bookmark" -> addBookmark()
                    it.title == "Bookmarks" -> showBookmarks()
                    it.title.toString().startsWith("Shizuku") -> requestShizuku()
                    it.title.toString().contains("root", true) -> authorizeRoot()
                }
                true
            }
            show()
        }
    }

    private fun showMoreMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Atualizar")
            menu.add("Pesquisar/filtrar")
            menu.add("Ir para caminho")
            menu.add("Copiar caminho atual")
            menu.add("Configurações")
            menu.add("Ordenar por nome")
            menu.add("Ordenar por tipo")
            menu.add("Ordenar por tamanho")
            menu.add("Ordenar por data")
            menu.add(if (controller.pane().showHidden) "Ocultar arquivos ocultos" else "Mostrar arquivos ocultos")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Atualizar" -> refresh(controller.activePane)
                    "Pesquisar/filtrar" -> showFilterDialog()
                    "Ir para caminho" -> showPathJump()
                    "Copiar caminho atual" -> copyToClipboard("Caminho", controller.pane().current.displayPath)
                    "Configurações" -> startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    "Ordenar por nome" -> setSort(SortField.NAME)
                    "Ordenar por tipo" -> setSort(SortField.TYPE)
                    "Ordenar por tamanho" -> setSort(SortField.SIZE)
                    "Ordenar por data" -> setSort(SortField.DATE)
                    else -> { controller.pane().showHidden = !controller.pane().showHidden; refresh(controller.activePane) }
                }
                true
            }
            show()
        }
    }

    private fun setSort(field: SortField) {
        val pane = controller.pane()
        if (pane.sortField == field) pane.ascending = !pane.ascending else { pane.sortField = field; pane.ascending = true }
        refresh(controller.activePane)
    }

    private fun showFilterDialog() {
        val input = EditText(this).apply { setText(controller.pane().filter); hint = "texto, !texto, /regex ou !/regex" }
        AlertDialog.Builder(this).setTitle("Filtro").setView(input).setPositiveButton("Aplicar") { _, _ ->
            controller.pane().filter = input.text.toString(); refresh(controller.activePane)
        }.setNeutralButton("Limpar") { _, _ -> controller.pane().filter = ""; refresh(controller.activePane) }.setNegativeButton("Cancelar", null).show()
    }

    private fun showPathJump() {
        val currentPath = (controller.pane().current as? FileLocation.Direct)?.path.orEmpty()
        val input = EditText(this).apply { setText(currentPath); setSingleLine(); hint = "/storage/emulated/0" }
        AlertDialog.Builder(this).setTitle("Ir para caminho").setView(input).setPositiveButton("Ir") { _, _ ->
            navigate(controller.activePane, FileLocation.Direct(input.text.toString()))
        }.setNegativeButton("Cancelar", null).show()
    }

    @Suppress("DEPRECATION")
    private fun requestSafTree() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQ_TREE)
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) { toast("Acesso a todos os arquivos já autorizado"); return }
            AlertDialog.Builder(this).setTitle("Acesso a arquivos")
                .setMessage("Necessário para gerenciar o armazenamento compartilhado. O Android ainda bloqueia dados privados de outros apps.")
                .setPositiveButton("Abrir configurações") { _, _ ->
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                    runCatching { startActivity(intent) }.onFailure { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                }.setNegativeButton("Agora não", null).show()
        }
    }

    private fun requestShizuku() {
        runCatching {
            if (graph.shizuku.hasPermission()) { graph.shizuku.bindService(); toast(graph.shizuku.status()) }
            else graph.shizuku.requestPermission(REQ_SHIZUKU)
        }.onFailure(::showError)
    }

    private fun authorizeRoot() {
        AlertDialog.Builder(this).setTitle("Autorizar root")
            .setMessage("O superusuário dá acesso amplo ao dispositivo. O Forge Manager só o usará após esta autorização explícita.")
            .setPositiveButton("Solicitar") { _, _ -> scope.launch {
                val ok = runCatching { withContext(Dispatchers.IO) { graph.root.authorize() } }.getOrDefault(false)
                toast(if (ok) "Root autorizado" else "Root indisponível ou negado")
            }}.setNegativeButton("Cancelar", null).show()
    }

    private fun addBookmark() {
        val preferences = getSharedPreferences("bookmarks", MODE_PRIVATE)
        val entries = readBookmarks().map(::encodeBookmark).toMutableSet()
        entries += encodeBookmark(controller.pane().current)
        preferences.edit().putStringSet("entries_v2", entries).remove("paths").apply()
        toast("Bookmark adicionado")
    }

    private fun showBookmarks() {
        val values = readBookmarks().distinctBy { it.displayPath }.sortedBy { it.displayPath.lowercase(Locale.ROOT) }
        if (values.isEmpty()) { toast("Nenhum bookmark"); return }
        AlertDialog.Builder(this).setTitle("Bookmarks").setItems(values.map { it.displayPath }.toTypedArray()) { _, which ->
            navigate(controller.activePane, values[which])
        }.setNegativeButton("Fechar", null).show()
    }

    private fun readBookmarks(): List<FileLocation> {
        val preferences = getSharedPreferences("bookmarks", MODE_PRIVATE)
        val encoded = preferences.getStringSet("entries_v2", emptySet()).orEmpty()
        val migrated = if (encoded.isEmpty()) {
            preferences.getStringSet("paths", emptySet()).orEmpty().map { FileLocation.Direct(it) }
        } else emptyList()
        val decoded = encoded.mapNotNull(::decodeBookmark)
        val combined = (decoded + migrated).distinctBy { it.displayPath }
        if (migrated.isNotEmpty()) {
            preferences.edit().putStringSet("entries_v2", combined.map(::encodeBookmark).toSet()).remove("paths").apply()
        }
        return combined
    }

    private fun encodeBookmark(location: FileLocation): String = JSONObject().apply {
        put("v", 2)
        when (location) {
            is FileLocation.Direct -> {
                put("type", "direct")
                put("path", location.path)
            }
            is FileLocation.Archive -> {
                put("type", "archive")
                put("archivePath", location.archivePath)
                put("entryPath", location.entryPath)
            }
            is FileLocation.Saf -> {
                put("type", "saf")
                put("documentUri", location.documentUri)
                put("treeUri", location.treeUri)
                put("displayPath", location.displayPath)
                put("parents", JSONArray().apply {
                    location.parents.forEach { parent ->
                        put(JSONObject().put("documentUri", parent.documentUri).put("displayPath", parent.displayPath))
                    }
                })
            }
        }
    }.toString()

    private fun decodeBookmark(raw: String): FileLocation? = runCatching {
        val json = JSONObject(raw)
        when (json.getString("type")) {
            "direct" -> FileLocation.Direct(json.getString("path"))
            "archive" -> FileLocation.Archive(json.getString("archivePath"), json.optString("entryPath"))
            "saf" -> {
                val parentJson = json.optJSONArray("parents") ?: JSONArray()
                val parents = ArrayList<FileLocation.SafParent>(parentJson.length())
                for (index in 0 until parentJson.length()) {
                    val value = parentJson.getJSONObject(index)
                    parents += FileLocation.SafParent(value.getString("documentUri"), value.getString("displayPath"))
                }
                FileLocation.Saf(
                    documentUri = json.getString("documentUri"),
                    treeUri = json.getString("treeUri"),
                    parents = parents,
                    displayPath = json.getString("displayPath")
                )
            }
            else -> null
        }
    }.getOrNull()

    private fun requestInitialStorageAccess() {
        requestLegacyPermissionIfNeeded()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            window.decorView.post { requestAllFilesAccess() }
        }
    }

    private fun requestLegacyPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
        }
    }

    private fun copyToClipboard(label: String, value: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        toast("Copiado")
    }

    private fun ui(id: PaneId) = if (id == PaneId.LEFT) leftUi else rightUi
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun showError(error: Throwable) {
        val message = when (error) {
            is FileAccessException, is SecurityException, is IllegalArgumentException -> error.message ?: "Operação recusada"
            else -> "A operação falhou. Detalhes disponíveis no log de depuração."
        }
        AlertDialog.Builder(this).setTitle("Não foi possível concluir").setMessage(message).setPositiveButton("OK", null).show()
    }

    private data class PaneUi(val root: View, val header: View, val path: TextView, val list: ListView, val adapter: FileListAdapter)

    companion object {
        const val EXTRA_OPEN_PATH = "open_path"
        private const val REQ_TREE = 100
        private const val REQ_SHIZUKU = 101
        private const val REQ_STORAGE = 102
    }
}
