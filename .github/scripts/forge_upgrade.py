from pathlib import Path

path = Path("app/src/main/java/com/forgemanager/app/MainActivity.kt")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    text = text.replace(old, new, 1)


replace_once(
    "import com.forgemanager.app.core.file.OperationProgress\n",
    "import com.forgemanager.app.core.file.OperationProgress\nimport com.forgemanager.app.core.file.putFileLocation\n",
    "location intent import",
)
replace_once(
    "import com.forgemanager.app.features.editor.TextEditorActivity\n",
    "import com.forgemanager.app.features.editor.TextEditorActivity\nimport com.forgemanager.app.features.editor.HtmlPreviewActivity\nimport com.forgemanager.app.features.viewer.ImageViewerActivity\nimport com.forgemanager.app.features.terminal.TerminalActivity\n",
    "viewer imports",
)
replace_once("        requestLegacyPermissionIfNeeded()\n", "        requestInitialStorageAccess()\n", "startup storage access")
replace_once(
    "    override fun onResume() {\n        super.onResume()\n        if (::controller.isInitialized) refresh(controller.activePane)\n    }\n",
    '''    override fun onResume() {
        super.onResume()
        if (::controller.isInitialized) refresh(controller.activePane)
    }

    @Deprecated("Android back compatibility")
    override fun onBackPressed() {
        handleBackNavigation()
    }
''',
    "system back handler",
)
replace_once(
    "        findViewById<View>(R.id.backButton).setOnClickListener { controller.back()?.let { refresh(controller.activePane) } }\n",
    "        findViewById<View>(R.id.backButton).setOnClickListener { handleBackNavigation() }\n",
    "bottom back handler",
)

start = text.index("    private fun openNode(id: PaneId, node: FileNode) {")
end = text.index("    private fun showSelectionActions(reference: FileNode) {", start)
new_open = '''    private fun openNode(id: PaneId, node: FileNode) {
        if (node.isDirectory) { navigate(id, node.location); return }
        val extension = FileTypeClassifier.extensionOf(node.name)
        val kind = FileTypeClassifier.classify(node.name, false)
        val zipContainers = setOf("zip", "jar", "apk", "aar", "apks", "xapk", "apkm", "aab", "epub")

        if (node.location is FileLocation.Direct && extension in zipContainers) {
            val archivePath = node.location.path
            if (extension == "apk") {
                AlertDialog.Builder(this).setTitle(node.name)
                    .setItems(arrayOf("Abrir como ZIP", "Informações do APK", "Abrir com…")) { _, which ->
                        when (which) {
                            0 -> navigate(id, FileLocation.Archive(archivePath))
                            1 -> startActivity(Intent(this, ApkInspectorActivity::class.java).putExtra("path", archivePath))
                            2 -> openWith(node)
                        }
                    }.show()
            } else navigate(id, FileLocation.Archive(archivePath))
            return
        }

        when (kind) {
            FileKind.IMAGE -> startActivity(Intent(this, ImageViewerActivity::class.java).putFileLocation(node.location, node.name))
            FileKind.CODE, FileKind.SCRIPT, FileKind.MARKDOWN, FileKind.TEXT, FileKind.XML, FileKind.CONFIG -> openTextEditor(node)
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
                if (direct != null) startActivity(Intent(this, DexInspectorActivity::class.java).putExtra("path", direct.path))
                else openHexEditor(node)
            }
            FileKind.VIDEO, FileKind.AUDIO, FileKind.PDF, FileKind.DOCUMENT, FileKind.SPREADSHEET,
            FileKind.PRESENTATION, FileKind.FONT, FileKind.CERTIFICATE -> openWith(node)
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

'''
text = text[:start] + new_open + text[end:]

replace_once(
    '            "Abrir com",\n            "Selecionar todos",\n',
    '            "Abrir com",\n            "Visualizar",\n            "Editar como texto/código",\n            "Editor hexadecimal",\n            "Selecionar todos",\n',
    "selection editor actions",
)
replace_once(
    '                "Abrir com" -> openWith(items.singleOrNull())\n                "Selecionar todos" ->',
    '                "Abrir com" -> openWith(items.singleOrNull())\n                "Visualizar" -> items.singleOrNull()?.let(::openInternalViewer) ?: toast("Selecione um arquivo")\n                "Editar como texto/código" -> items.singleOrNull()?.let(::openTextEditor) ?: toast("Selecione um arquivo")\n                "Editor hexadecimal" -> items.singleOrNull()?.let(::openHexEditor) ?: toast("Selecione um arquivo")\n                "Selecionar todos" ->',
    "selection action handlers",
)

marker = "    private fun compareText(items: List<FileNode>) {"
helpers = '''    private fun openTextEditor(node: FileNode) {
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
            FileKind.CODE, FileKind.SCRIPT, FileKind.MARKDOWN, FileKind.TEXT, FileKind.XML, FileKind.CONFIG -> openTextEditor(node)
            FileKind.DEX, FileKind.DATABASE, FileKind.EXECUTABLE -> openHexEditor(node)
            else -> openWith(node)
        }
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

'''
if text.count(marker) != 1:
    raise SystemExit("helper marker mismatch")
text = text.replace(marker, helpers + marker, 1)

start = text.index("    private fun openWith(item: FileNode?) {")
end = text.index("    private fun showMainMenu(anchor: View) {", start)
new_open_with = '''    private fun openWith(item: FileNode?) {
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

'''
text = text[:start] + new_open_with + text[end:]

replace_once(
    '            menu.add("Aplicativos instalados")\n            menu.add("Adicionar bookmark")\n',
    '            menu.add("Aplicativos instalados")\n            menu.add("Terminal")\n            menu.add("Adicionar bookmark")\n',
    "terminal menu item",
)
replace_once(
    '                    it.title == "Aplicativos instalados" -> startActivity(Intent(this@MainActivity, InstalledAppsActivity::class.java))\n                    it.title == "Adicionar bookmark" -> addBookmark()\n',
    '''                    it.title == "Aplicativos instalados" -> startActivity(Intent(this@MainActivity, InstalledAppsActivity::class.java))
                    it.title == "Terminal" -> {
                        val working = (controller.pane().current as? FileLocation.Direct)?.path ?: Environment.getExternalStorageDirectory().path
                        startActivity(Intent(this@MainActivity, TerminalActivity::class.java).putExtra(TerminalActivity.EXTRA_WORKING_DIRECTORY, working))
                    }
                    it.title == "Adicionar bookmark" -> addBookmark()
''',
    "terminal menu handler",
)

marker = "    private fun requestLegacyPermissionIfNeeded() {"
initial_access = '''    private fun requestInitialStorageAccess() {
        requestLegacyPermissionIfNeeded()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            window.decorView.post { requestAllFilesAccess() }
        }
    }

'''
if text.count(marker) != 1:
    raise SystemExit("storage helper marker mismatch")
text = text.replace(marker, initial_access + marker, 1)
path.write_text(text, encoding="utf-8")

editor = Path("app/src/main/java/com/forgemanager/app/features/editor/TextEditorActivity.kt")
editor_text = editor.read_text(encoding="utf-8")
if "import android.content.Intent\n" not in editor_text:
    editor_text = editor_text.replace("import android.app.AlertDialog\n", "import android.app.AlertDialog\nimport android.content.Intent\n", 1)
editor.write_text(editor_text, encoding="utf-8")

highlighter = Path("app/src/main/java/com/forgemanager/app/features/editor/SyntaxHighlighter.kt")
highlighter_text = highlighter.read_text(encoding="utf-8").replace(
    "Editable.SPAN_EXCLUSIVE_EXCLUSIVE", "android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE"
)
highlighter.write_text(highlighter_text, encoding="utf-8")
