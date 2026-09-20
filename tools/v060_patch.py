from pathlib import Path

p = Path("app/src/main/java/com/forgemanager/app/MainActivity.kt")
s = p.read_text()


def rep(old: str, new: str) -> None:
    global s
    if old not in s:
        raise SystemExit("expected block not found:\n" + old[:500])
    s = s.replace(old, new, 1)


rep(
    "import com.forgemanager.app.features.resources.BinaryResourceEditorActivity\n",
    "import com.forgemanager.app.features.resources.BinaryResourceEditorActivity\n"
    "import com.forgemanager.app.features.resources.ApkResourceStudioActivity\n"
    "import com.forgemanager.app.features.settings.SettingsActivity\n"
    "import com.forgemanager.app.features.settings.UiPreferences\n"
    "import com.forgemanager.app.features.torrent.TorrentInspectorActivity\n",
)

rep(
    "        controller = DualPaneController(initial, initial)\n"
    "        activePath = findViewById(R.id.activePath)\n",
    "        controller = DualPaneController(initial, initial)\n"
    "        val showHiddenDefault = UiPreferences.showHiddenDefault(this)\n"
    "        controller.pane(PaneId.LEFT).showHidden = showHiddenDefault\n"
    "        controller.pane(PaneId.RIGHT).showHidden = showHiddenDefault\n"
    "        activePath = findViewById(R.id.activePath)\n",
)

rep(
    "        bindToolbar()\n        activate(PaneId.LEFT)\n",
    "        bindToolbar()\n        applyUiPreferences()\n        activate(PaneId.LEFT)\n",
)

rep(
    "    override fun onResume() {\n"
    "        super.onResume()\n"
    "        if (::controller.isInitialized) refresh(controller.activePane)\n"
    "    }\n",
    "    override fun onResume() {\n"
    "        super.onResume()\n"
    "        if (::controller.isInitialized) {\n"
    "            applyUiPreferences()\n"
    "            leftUi.adapter.notifyDataSetChanged()\n"
    "            rightUi.adapter.notifyDataSetChanged()\n"
    "            refresh(controller.activePane)\n"
    "        }\n"
    "    }\n",
)

old_activate = """    private fun activate(id: PaneId) {
        controller.activate(id)
        leftUi.header.setBackgroundResource(if (id == PaneId.LEFT) R.drawable.bg_pane_header_active else R.drawable.bg_pane_header)
        rightUi.header.setBackgroundResource(if (id == PaneId.RIGHT) R.drawable.bg_pane_header_active else R.drawable.bg_pane_header)
        updateHeader()
    }

    private fun updateHeader() {
"""
new_activate = """    private fun activate(id: PaneId) {
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
"""
rep(old_activate, new_activate)

old_archive = """        if (node.location is FileLocation.Direct && extension in zipContainers) {
            val archivePath = node.location.path
            if (extension == "apk") {
                AlertDialog.Builder(this).setTitle(node.name)
                    .setItems(arrayOf("Abrir como ZIP", "APK Toolbox", "Informações do APK", "Abrir com…")) { _, which ->
                        when (which) {
                            0 -> navigate(id, FileLocation.Archive(archivePath))
                            1 -> startActivity(Intent(this, ApkToolboxActivity::class.java).putExtra(ApkToolboxActivity.EXTRA_APK_PATH, archivePath))
                            2 -> startActivity(Intent(this, ApkInspectorActivity::class.java).putExtra("path", archivePath))
                            3 -> openWith(node)
                        }
                    }.show()
            } else navigate(id, FileLocation.Archive(archivePath))
            return
        }
"""
new_archive = """        if (node.location is FileLocation.Direct && extension in zipContainers) {
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
"""
rep(old_archive, new_archive)

rep(
    "            FileKind.VIDEO, FileKind.AUDIO, FileKind.PDF, FileKind.DOCUMENT, FileKind.SPREADSHEET,\n"
    "            FileKind.PRESENTATION, FileKind.FONT, FileKind.CERTIFICATE -> openWith(node)\n"
    "            FileKind.EXECUTABLE, FileKind.DATABASE -> openHexEditor(node)\n"
    "            FileKind.APK, FileKind.ARCHIVE -> openWith(node)\n",
    "            FileKind.SUBTITLE -> openTextEditor(node)\n"
    "            FileKind.TORRENT -> startActivity(Intent(this, TorrentInspectorActivity::class.java).putFileLocation(node.location, node.name))\n"
    "            FileKind.KEY, FileKind.CERTIFICATE, FileKind.BACKUP -> showTextHexOpenDialog(node)\n"
    "            FileKind.VIDEO, FileKind.AUDIO, FileKind.PDF, FileKind.DOCUMENT, FileKind.SPREADSHEET,\n"
    "            FileKind.PRESENTATION, FileKind.FONT, FileKind.LINUX_PACKAGE, FileKind.MODEL3D -> openWith(node)\n"
    "            FileKind.EXECUTABLE, FileKind.DATABASE -> openHexEditor(node)\n"
    "            FileKind.APK, FileKind.ARCHIVE -> openWith(node)\n",
)

rep(
    "    private fun handleBackNavigation() {\n",
    "    private fun showTextHexOpenDialog(node: FileNode) {\n"
    "        AlertDialog.Builder(this).setTitle(node.name)\n"
    "            .setItems(arrayOf(\"Visualizar/editar como texto\", \"Editor hexadecimal\", \"Abrir com…\")) { _, which ->\n"
    "                when (which) {\n"
    "                    0 -> openTextEditor(node)\n"
    "                    1 -> openHexEditor(node)\n"
    "                    2 -> openWith(node)\n"
    "                }\n"
    "            }.show()\n"
    "    }\n\n"
    "    private fun handleBackNavigation() {\n",
)

old_delete = """    private fun confirmDelete(items: List<FileNode>) {
        AlertDialog.Builder(this).setTitle("Excluir ${items.size} item(ns)?")
            .setMessage("A exclusão é permanente e não pode ser desfeita.")
            .setPositiveButton("Excluir") { _, _ -> scope.launch {
                runCatching { withContext(Dispatchers.IO) { items.forEach { graph.resolver.backendFor(it.location, write = true).delete(it.location) } } }
                    .onSuccess { controller.pane().selection.clear(); refresh(controller.activePane) }.onFailure(::showError)
            }}.setNegativeButton("Cancelar", null).show()
    }
"""
new_delete = """    private fun confirmDelete(items: List<FileNode>) {
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
"""
rep(old_delete, new_delete)

rep(
    '            menu.add("Terminal PTY")\n            menu.add("Adicionar bookmark")\n',
    '            menu.add("Terminal PTY")\n            menu.add("Configurações")\n            menu.add("Adicionar bookmark")\n',
)

rep(
    '                    it.title == "Terminal PTY" -> {\n'
    '                        val working = (controller.pane().current as? FileLocation.Direct)?.path ?: Environment.getExternalStorageDirectory().path\n'
    '                        startActivity(Intent(this@MainActivity, TerminalActivity::class.java).putExtra(TerminalActivity.EXTRA_WORKING_DIRECTORY, working))\n'
    '                    }\n'
    '                    it.title == "Adicionar bookmark" -> addBookmark()\n',
    '                    it.title == "Terminal PTY" -> {\n'
    '                        val working = (controller.pane().current as? FileLocation.Direct)?.path ?: Environment.getExternalStorageDirectory().path\n'
    '                        startActivity(Intent(this@MainActivity, TerminalActivity::class.java).putExtra(TerminalActivity.EXTRA_WORKING_DIRECTORY, working))\n'
    '                    }\n'
    '                    it.title == "Configurações" -> startActivity(Intent(this@MainActivity, SettingsActivity::class.java))\n'
    '                    it.title == "Adicionar bookmark" -> addBookmark()\n',
)

rep(
    '            menu.add("Copiar caminho atual")\n            menu.add("Ordenar por nome")\n',
    '            menu.add("Copiar caminho atual")\n            menu.add("Configurações")\n            menu.add("Ordenar por nome")\n',
)

rep(
    '                    "Copiar caminho atual" -> copyToClipboard("Caminho", controller.pane().current.displayPath)\n'
    '                    "Ordenar por nome" -> setSort(SortField.NAME)\n',
    '                    "Copiar caminho atual" -> copyToClipboard("Caminho", controller.pane().current.displayPath)\n'
    '                    "Configurações" -> startActivity(Intent(this@MainActivity, SettingsActivity::class.java))\n'
    '                    "Ordenar por nome" -> setSort(SortField.NAME)\n',
)

p.write_text(s)
