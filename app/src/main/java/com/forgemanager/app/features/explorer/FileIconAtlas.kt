package com.forgemanager.app.features.explorer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import com.forgemanager.app.R

/**
 * Crops the icon atlas supplied for Forge Manager into per-format drawables.
 * The atlas contains 11 columns x 6 rows; missing cells are transparent.
 */
object FileIconAtlas {
    private const val COLS = 11
    private const val ROWS = 6
    private val cache = LruCache<String, Drawable>(96)
    @Volatile private var atlas: Bitmap? = null

    private val positions = mapOf(
        "folder" to Pair(0, 0), "zip" to Pair(1, 0), "rar" to Pair(2, 0), "7z" to Pair(3, 0),
        "tar" to Pair(4, 0), "gz" to Pair(5, 0), "apk" to Pair(6, 0), "aab" to Pair(7, 0),
        "xapb" to Pair(8, 0), "xapk" to Pair(9, 0), "dex" to Pair(10, 0),
        "smali" to Pair(0, 1), "xml" to Pair(1, 1), "arsc" to Pair(2, 1), "pdf" to Pair(3, 1),
        "word" to Pair(4, 1), "excel" to Pair(5, 1), "powerpoint" to Pair(6, 1), "txt" to Pair(7, 1),
        "markdown" to Pair(8, 1), "json" to Pair(9, 1),
        "yaml" to Pair(0, 2), "toml" to Pair(1, 2), "html" to Pair(2, 2), "css" to Pair(3, 2),
        "javascript" to Pair(4, 2), "typescript" to Pair(5, 2), "java" to Pair(6, 2), "kotlin" to Pair(7, 2),
        "python" to Pair(8, 2), "c" to Pair(9, 2),
        "cpp" to Pair(0, 3), "csharp" to Pair(1, 3), "rust" to Pair(2, 3), "go" to Pair(3, 3),
        "php" to Pair(4, 3), "ruby" to Pair(5, 3), "swift" to Pair(6, 3), "dart" to Pair(7, 3),
        "shell" to Pair(8, 3), "sql" to Pair(9, 3),
        "lua" to Pair(0, 4), "jar" to Pair(1, 4), "exe" to Pair(2, 4), "dll" to Pair(3, 4),
        "so" to Pair(4, 4), "bin" to Pair(5, 4), "db" to Pair(6, 4), "sqlite" to Pair(7, 4),
        "iso" to Pair(8, 4), "img" to Pair(9, 4),
        "font" to Pair(0, 5), "certificate" to Pair(1, 5), "key" to Pair(2, 5), "epub" to Pair(3, 5),
        "subtitle" to Pair(4, 5), "torrent" to Pair(5, 5), "backup" to Pair(6, 5), "linuxpkg" to Pair(7, 5),
        "model3d" to Pair(8, 5)
    )

    fun drawable(context: Context, name: String, isDirectory: Boolean, kind: FileKind): Drawable? {
        val key = keyFor(name, isDirectory, kind) ?: return null
        cache.get(key)?.let { return it }
        val bitmap = atlas ?: synchronized(this) {
            atlas ?: BitmapFactory.decodeResource(context.resources, R.drawable.fm_file_icon_atlas_v2).also { atlas = it }
        } ?: return null
        val position = positions[key] ?: return null
        val tileWidth = bitmap.width / COLS
        val tileHeight = bitmap.height / ROWS
        val left = position.first * tileWidth
        val top = position.second * tileHeight
        if (left + tileWidth > bitmap.width || top + tileHeight > bitmap.height) return null
        val crop = Bitmap.createBitmap(bitmap, left, top, tileWidth, tileHeight)
        return BitmapDrawable(context.resources, crop).also { cache.put(key, it) }
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
            kind == FileKind.CONFIG -> "json"
            else -> null
        }
    }
}
