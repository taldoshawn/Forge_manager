package com.forgemanager.app.features.explorer

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat

/**
 * Resolves the real per-format image assets supplied for Forge Manager.
 *
 * The atlas no longer draws replacement artwork programmatically. When an
 * original cropped asset exists it is returned directly. Unknown formats
 * deliberately return null so FileListAdapter can use its generic fallback.
 */
object FileIconAtlas {
    fun drawable(context: Context, name: String, isDirectory: Boolean, kind: FileKind): Drawable? {
        val key = keyFor(name, isDirectory, kind) ?: return null
        val resId = context.resources.getIdentifier("fm_format_$key", "drawable", context.packageName)
        if (resId == 0) return null
        return ContextCompat.getDrawable(context, resId)
    }

    fun keyFor(name: String, isDirectory: Boolean, kind: FileKind): String? {
        if (isDirectory) return "folder"
        val lower = name.lowercase()
        val ext = FileTypeClassifier.extensionOf(name)
        return when {
            lower.endsWith(".pkg.tar.zst") || lower.endsWith(".pkg.tar.xz") -> "linuxpkg"
            ext in setOf("zip", "aar") -> "zip"
            ext == "rar" -> "rar"
            ext == "7z" -> "7z"
            ext == "tar" -> "tar"
            ext in setOf("gz", "tgz", "bz2", "xz", "zst") -> "gz"
            ext == "jar" -> "jar"
            ext == "epub" -> "epub"
            ext == "apk" -> "apk"
            ext == "aab" -> "aab"
            ext in setOf("xapk", "apkm") -> "xapk"
            ext in setOf("apks", "xapb") -> "xapb"
            ext in setOf("dex", "vdex", "odex") -> "dex"
            ext == "smali" -> "smali"
            ext in setOf("xml", "xsl", "xslt", "plist", "axml") -> "xml"
            ext == "arsc" -> "arsc"
            ext == "pdf" -> "pdf"
            ext in setOf("doc", "docx", "odt", "rtf", "pages") -> "word"
            ext in setOf("xls", "xlsx", "ods", "numbers") -> "excel"
            ext in setOf("ppt", "pptx", "odp", "keynote") -> "powerpoint"
            ext in setOf("txt", "log", "csv", "ini", "cfg", "conf", "properties", "prop") -> "txt"
            ext in setOf("md", "markdown", "mdown", "mkd") -> "markdown"
            ext in setOf("json", "json5") -> "json"
            ext in setOf("yaml", "yml") -> "yaml"
            ext == "toml" -> "toml"
            ext in setOf("html", "htm", "xhtml", "vue", "svelte") -> "html"
            ext in setOf("css", "scss", "sass", "less") -> "css"
            ext in setOf("js", "mjs", "cjs", "jsx") -> "javascript"
            ext in setOf("ts", "tsx") -> "typescript"
            ext == "java" -> "java"
            ext in setOf("kt", "kts") -> "kotlin"
            ext == "py" -> "python"
            ext in setOf("c", "h") -> "c"
            ext in setOf("cc", "cpp", "cxx", "hpp") -> "cpp"
            ext == "cs" -> "csharp"
            ext == "rs" -> "rust"
            ext == "go" -> "go"
            ext == "php" -> "php"
            ext == "rb" -> "ruby"
            ext == "swift" -> "swift"
            ext == "dart" -> "dart"
            ext in setOf("sh", "bash", "zsh", "fish", "bat", "cmd", "ps1") -> "shell"
            ext == "sql" -> "sql"
            ext == "lua" -> "lua"
            ext == "exe" -> "exe"
            ext == "dll" -> "dll"
            ext == "so" -> "so"
            ext in setOf("bin", "elf", "msi", "appimage") -> "bin"
            ext in setOf("db", "realm", "mdb", "accdb") -> "db"
            ext in setOf("sqlite", "sqlite3") -> "sqlite"
            ext == "iso" -> "iso"
            ext in setOf("img", "simg", "raw") -> "img"
            ext in setOf("ttf", "otf", "woff", "woff2") -> "font"
            ext in setOf("cer", "crt", "der", "p12", "pfx", "jks", "bks", "pem") -> "certificate"
            ext in setOf("key", "keystore", "pub") -> "key"
            ext in setOf("srt", "ass", "ssa", "vtt", "sub") -> "subtitle"
            ext == "torrent" -> "torrent"
            ext in setOf("bak", "backup", "old", "orig") -> "backup"
            ext in setOf("deb", "rpm", "ipk", "pkg") -> "linuxpkg"
            ext in setOf("obj", "fbx", "stl", "gltf", "glb", "3ds", "blend", "dae", "ply") -> "model3d"
            kind == FileKind.TEXT -> "txt"
            else -> null
        }
    }
}
