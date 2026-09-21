package com.forgemanager.app.features.explorer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import com.forgemanager.app.core.file.FileLocation
import com.forgemanager.app.core.file.FileNode
import com.forgemanager.app.core.file.putFileLocation
import com.forgemanager.app.features.apktools.ApkToolboxActivity
import com.forgemanager.app.features.dex.DexInspectorActivity
import com.forgemanager.app.features.dex.SmaliStudioActivity
import com.forgemanager.app.features.editor.EditorLanguage
import com.forgemanager.app.features.editor.EditorProfile
import com.forgemanager.app.features.editor.HexViewerActivity
import com.forgemanager.app.features.editor.HtmlPreviewActivity
import com.forgemanager.app.features.editor.TextEditorActivity
import com.forgemanager.app.features.resources.BinaryResourceEditorActivity
import com.forgemanager.app.features.viewer.DocumentViewerActivity
import com.forgemanager.app.features.viewer.ImageViewerActivity

/** Explicit, non-executing "Open as…" router for the active file. */
object OpenAsController {
    fun show(
        activity: Activity,
        node: FileNode,
        navigateArchive: ((FileLocation.Archive) -> Unit)?,
        openAutomatic: (FileNode) -> Unit,
        openExternal: (FileNode) -> Unit
    ) {
        if (node.isDirectory) return
        val actions = mutableListOf<Action>()
        actions += Action("Texto / código") { openText(activity, node, EditorLanguage.PLAIN) }
        actions += Action("Código com perfil…") { chooseCodeLanguage(activity, node) }
        actions += Action("Documento / PDF") {
            activity.startActivity(Intent(activity, DocumentViewerActivity::class.java).putFileLocation(node.location, node.name))
        }
        actions += Action("Hexadecimal") {
            activity.startActivity(Intent(activity, HexViewerActivity::class.java).putFileLocation(node.location, node.name))
        }
        actions += Action("Imagem") {
            activity.startActivity(Intent(activity, ImageViewerActivity::class.java).putFileLocation(node.location, node.name))
        }
        actions += Action("XML (texto)") { openText(activity, node, EditorLanguage.XML) }
        actions += Action("HTML / Markdown") {
            val ext = FileTypeClassifier.extensionOf(node.name)
            if (ext in setOf("html", "htm", "xhtml")) {
                activity.startActivity(Intent(activity, HtmlPreviewActivity::class.java).putFileLocation(node.location, node.name))
            } else openText(activity, node, EditorLanguage.MARKDOWN)
        }
        actions += Action("AXML / ARSC — Pool/XML") {
            activity.startActivity(Intent(activity, BinaryResourceEditorActivity::class.java).putFileLocation(node.location, node.name))
        }
        actions += Action("DEX / Smali") { openDex(activity, node) }

        val direct = node.location as? FileLocation.Direct
        if (direct != null && FileTypeClassifier.extensionOf(node.name) in setOf("apk", "xapk", "apkm", "apks", "aab", "zip")) {
            actions += Action("APK Toolbox — extrair/recompilar/converter") {
                activity.startActivity(Intent(activity, ApkToolboxActivity::class.java).putExtra(ApkToolboxActivity.EXTRA_APK_PATH, direct.path))
            }
        }
        if (direct != null && navigateArchive != null) {
            actions += Action("Arquivo compactado") { navigateArchive(FileLocation.Archive(direct.path)) }
        }
        actions += Action("Viewer interno automático") { openAutomatic(node) }
        actions += Action("Aplicativo externo") { openExternal(node) }

        AlertDialog.Builder(activity)
            .setTitle("Abrir como… — ${node.name}")
            .setItems(actions.map { it.label }.toTypedArray()) { _, which -> actions.getOrNull(which)?.run?.invoke() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openText(activity: Activity, node: FileNode, language: EditorLanguage) {
        val profileName = if (language == EditorLanguage.PLAIN) {
            node.name
        } else {
            "${node.name} [como ${languageLabel(language)}].${EditorProfile.extensionFor(language)}"
        }
        activity.startActivity(Intent(activity, TextEditorActivity::class.java).putFileLocation(node.location, profileName))
    }

    private fun chooseCodeLanguage(activity: Activity, node: FileNode) {
        val languages = listOf(
            EditorLanguage.PYTHON, EditorLanguage.JAVA, EditorLanguage.KOTLIN, EditorLanguage.SMALI,
            EditorLanguage.HTML, EditorLanguage.CSS, EditorLanguage.JAVASCRIPT, EditorLanguage.TYPESCRIPT,
            EditorLanguage.JSON, EditorLanguage.YAML, EditorLanguage.TOML, EditorLanguage.MARKDOWN,
            EditorLanguage.C, EditorLanguage.CPP, EditorLanguage.CSHARP, EditorLanguage.RUST,
            EditorLanguage.GO, EditorLanguage.PHP, EditorLanguage.RUBY, EditorLanguage.SWIFT,
            EditorLanguage.DART, EditorLanguage.SHELL, EditorLanguage.SQL, EditorLanguage.LUA
        )
        AlertDialog.Builder(activity)
            .setTitle("Perfil de código")
            .setItems(languages.map(::languageLabel).toTypedArray()) { _, which ->
                languages.getOrNull(which)?.let { openText(activity, node, it) }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openDex(activity: Activity, node: FileNode) {
        val direct = node.location as? FileLocation.Direct
        if (direct == null) {
            activity.startActivity(Intent(activity, SmaliStudioActivity::class.java).putFileLocation(node.location, node.name))
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("DEX — ${node.name}")
            .setItems(arrayOf("Smali / rebuild", "DEX Editor Plus / multi-DEX")) { _, which ->
                when (which) {
                    0 -> activity.startActivity(Intent(activity, SmaliStudioActivity::class.java).putFileLocation(node.location, node.name))
                    1 -> activity.startActivity(Intent(activity, DexInspectorActivity::class.java).putExtra("path", direct.path))
                }
            }
            .show()
    }

    private fun languageLabel(language: EditorLanguage): String = when (language) {
        EditorLanguage.CSHARP -> "C#"
        EditorLanguage.CPP -> "C++"
        EditorLanguage.JAVASCRIPT -> "JavaScript"
        EditorLanguage.TYPESCRIPT -> "TypeScript"
        else -> language.name.lowercase().replaceFirstChar { it.uppercase() }
    }

    private data class Action(val label: String, val run: () -> Unit)
}
