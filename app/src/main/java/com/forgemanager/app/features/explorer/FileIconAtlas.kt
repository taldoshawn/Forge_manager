package com.forgemanager.app.features.explorer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache

/**
 * Resolves the per-format artwork supplied for Forge Manager.
 *
 * The uploaded PNGs were cut from a white sheet and some of them still contain
 * opaque white antialias pixels around the transparent silhouette. Android
 * renders those pixels very clearly on dark/colored backgrounds. The atlas
 * cleans only the outer one-pixel fringe and isolated cutout specks, keeps the
 * original interior artwork intact, then caches the result.
 */
object FileIconAtlas {
    private val cache = object : LruCache<Int, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.allocationByteCount
    }

    fun drawable(context: Context, name: String, isDirectory: Boolean, kind: FileKind): Drawable? {
        val key = keyFor(name, isDirectory, kind) ?: return null
        val resId = context.resources.getIdentifier("fm_format_$key", "drawable", context.packageName)
        if (resId == 0) return null
        val cached = synchronized(cache) { cache.get(resId) }
        val bitmap = cached ?: run {
            val decoded = BitmapFactory.decodeResource(context.resources, resId) ?: return null
            val cleaned = cleanCutout(decoded)
            synchronized(cache) { cache.put(resId, cleaned) }
            cleaned
        }
        return BitmapDrawable(context.resources, bitmap)
    }

    /** Removes white matte contamination without deleting legitimate white artwork. */
    private fun cleanCutout(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        if (width <= 2 || height <= 2) return source
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val original = pixels.copyOf()

        fun alphaAt(x: Int, y: Int): Int = Color.alpha(original[y * width + x])
        fun isCore(x: Int, y: Int): Boolean {
            if (x <= 0 || y <= 0 || x >= width - 1 || y >= height - 1 || alphaAt(x, y) == 0) return false
            for (dy in -1..1) for (dx in -1..1) {
                if (alphaAt(x + dx, y + dy) == 0) return false
            }
            return true
        }

        // Remove tiny isolated pixels left by the old white-background crop.
        repeat(2) {
            val snapshot = pixels.copyOf()
            for (y in 1 until height - 1) for (x in 1 until width - 1) {
                val index = y * width + x
                if (Color.alpha(snapshot[index]) == 0) continue
                var neighbours = 0
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    if (Color.alpha(snapshot[(y + dy) * width + (x + dx)]) > 0) neighbours++
                }
                if (neighbours <= 1) pixels[index] = Color.TRANSPARENT
            }
        }

        // Defringe boundary pixels by borrowing color from the nearest interior
        // pixel. This fixes the visible white outline while preserving white
        // document icons because their own interior is also white.
        for (y in 0 until height) for (x in 0 until width) {
            val index = y * width + x
            if (Color.alpha(original[index]) == 0) continue
            var boundary = x == 0 || y == 0 || x == width - 1 || y == height - 1
            if (!boundary) {
                loop@ for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    if (alphaAt(x + dx, y + dy) == 0) { boundary = true; break@loop }
                }
            }
            if (!boundary) continue

            var replacement: Int? = null
            var bestDistance = Int.MAX_VALUE
            for (radius in 1..3) {
                for (dy in -radius..radius) for (dx in -radius..radius) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 1 until width - 1 || ny !in 1 until height - 1) continue
                    if (!isCore(nx, ny)) continue
                    val distance = dx * dx + dy * dy
                    if (distance < bestDistance) {
                        bestDistance = distance
                        replacement = original[ny * width + nx]
                    }
                }
                if (replacement != null) break
            }
            replacement?.let { core ->
                val alpha = Color.alpha(original[index]).coerceAtMost(218)
                pixels[index] = Color.argb(alpha, Color.red(core), Color.green(core), Color.blue(core))
            }
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
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
