package com.forgemanager.app.features.explorer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import java.util.Locale
import java.util.zip.ZipInputStream

/** Resolves the original Forge Manager format artwork plus the v4 66-icon expansion pack. */
object FileIconAtlas {
    private val resourceCache = object : LruCache<Int, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.allocationByteCount
    }
    private val extraCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val extraLock = Any()
    @Volatile private var extraPackLoaded = false

    fun drawable(context: Context, name: String, isDirectory: Boolean, kind: FileKind): Drawable? {
        if (!isDirectory) {
            val extraEntry = extraEntryFor(name)
            if (extraEntry != null) {
                ensureExtraPack(context)
                val bitmap = synchronized(extraCache) { extraCache.get(extraEntry) }
                if (bitmap != null) return BitmapDrawable(context.resources, bitmap)
            }
        }

        val key = keyFor(name, isDirectory, kind) ?: return null
        val resId = context.resources.getIdentifier("fm_format_$key", "drawable", context.packageName)
        if (resId == 0) return null
        val cached = synchronized(resourceCache) { resourceCache.get(resId) }
        val bitmap = cached ?: run {
            val decoded = BitmapFactory.decodeResource(context.resources, resId) ?: return null
            val cleaned = cleanCutout(decoded)
            synchronized(resourceCache) { resourceCache.put(resId, cleaned) }
            cleaned
        }
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun ensureExtraPack(context: Context) {
        if (extraPackLoaded) return
        synchronized(extraLock) {
            if (extraPackLoaded) return
            runCatching {
                context.assets.open(EXTRA_ASSET_ZIP).use { raw ->
                    ZipInputStream(raw.buffered(64 * 1024)).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (!entry.isDirectory && entry.name.substringAfterLast('/').lowercase(Locale.ROOT).endsWith(".png")) {
                                val shortName = entry.name.substringAfterLast('/')
                                BitmapFactory.decodeStream(zip)?.let { decoded ->
                                    val cleaned = cleanCutout(decoded)
                                    synchronized(extraCache) { extraCache.put(shortName, cleaned) }
                                }
                            }
                            zip.closeEntry()
                        }
                    }
                }
            }
            // Missing/corrupt optional pack must never make the file list crash.
            extraPackLoaded = true
        }
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
            for (dy in -1..1) for (dx in -1..1) if (alphaAt(x + dx, y + dy) == 0) return false
            return true
        }

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
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 1 until width - 1 || ny !in 1 until height - 1 || !isCore(nx, ny)) continue
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

    /** File names inside icones_recortados_sem_fundo_v4.zip. */
    fun extraEntryFor(name: String): String? {
        val upperName = name.uppercase(Locale.ROOT)
        val ext = FileTypeClassifier.extensionOf(name)
        return when {
            ext == "flac" -> "01_FLAC.png"
            ext == "wav" -> "02_WAV.png"
            ext == "ogg" -> "03_OGG.png"
            ext == "aac" -> "04_AAC.png"
            ext == "m4a" -> "05_M4A.png"
            ext == "opus" -> "06_OPUS.png"
            ext in setOf("aiff", "aif") -> "07_AIFF.png"
            ext == "m3u" -> "08_M3U.png"
            ext == "m3u8" -> "10_M3U8.png"
            ext == "cue" -> "11_CUE.png"
            ext == "xpi" -> "12_XPI.png"
            ext == "crx" -> "13_CRX.png"
            ext == "vsix" -> "14_VSIX.png"
            ext == "apkm" -> "15_APKM.png"
            ext == "apks" -> "16_APKS.png"
            ext == "apex" -> "17_APEX.png"
            ext == "ipa" -> "18_IPA.png"
            ext == "deb" -> "19_DEB.png"
            ext == "rpm" -> "21_RPM.png"
            ext == "cab" -> "22_CAB.png"
            ext == "msi" -> "23_MSI.png"
            ext == "dmg" -> "24_DMG.png"
            ext == "pkg" -> "25_PKG.png"
            ext == "appimage" -> "26_APPIMAGE.png"
            ext == "bat" -> "27_BAT.png"
            ext == "cmd" -> "28_CMD.png"
            ext == "ps1" -> "29_PS1.png"
            ext == "reg" -> "30_REG.png"
            ext == "ini" -> "31_INI.png"
            ext in setOf("conf", "cfg") -> "33_CONF.png"
            ext == "env" || name.equals(".env", true) -> "34_ENV.png"
            ext == "log" -> "35_LOG.png"
            ext == "csv" -> "36_CSV.png"
            ext == "tsv" -> "37_TSV.png"
            ext == "rtf" -> "38_RTF.png"
            ext == "doc" -> "39_DOC.png"
            ext == "xls" -> "40_XLS.png"
            ext == "ppt" -> "41_PPT.png"
            ext == "odt" -> "43_ODT.png"
            ext == "ods" -> "44_ODS.png"
            ext == "odp" -> "45_ODP.png"
            ext == "wasm" -> "46_WASM.png"
            ext == "class" -> "47_CLASS.png"
            ext == "vbs" -> "48_VBS.png"
            ext in setOf("asm", "s", "inc") -> "49_ASM.png"
            ext == "patch" -> "50_PATCH.png"
            ext == "diff" -> "51_DIFF.png"
            ext == "lock" -> "52_LOCK.png"
            upperName == "README" || upperName.startsWith("README.") -> "54_README.png"
            upperName == "LICENSE" || upperName.startsWith("LICENSE.") -> "55_LICENSE.png"
            ext == "ics" -> "56_ICS.png"
            ext == "vcf" -> "57_VCF.png"
            ext == "hex" -> "58_HEX.png"
            ext == "ass" -> "59_ASS.png"
            ext == "vtt" -> "60_VTT.png"
            ext == "smi" -> "61_SMI.png"
            ext == "sfv" -> "62_SFV.png"
            ext == "nfo" -> "63_NFO.png"
            ext == "psd" -> "65_PSD.png"
            ext == "svg" -> "66_SVG.png"
            else -> null
        }
    }

    fun keyFor(name: String, isDirectory: Boolean, kind: FileKind): String? {
        if (isDirectory) return "folder"
        val lower = name.lowercase()
        val ext = FileTypeClassifier.extensionOf(name)
        return when {
            lower.endsWith(".pkg.tar.zst") || lower.endsWith(".pkg.tar.xz") -> "linuxpkg"
            ext in setOf("zip", "aar", "xpi", "crx", "vsix", "cab", "ipa") -> "zip"
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
            ext == "apex" -> "apk"
            ext in setOf("dex", "vdex", "odex") -> "dex"
            ext == "smali" -> "smali"
            ext in setOf("xml", "xsl", "xslt", "plist", "axml") -> "xml"
            ext == "arsc" -> "arsc"
            ext == "pdf" -> "pdf"
            ext in setOf("doc", "docx", "odt", "rtf", "pages") -> "word"
            ext in setOf("xls", "xlsx", "ods", "numbers") -> "excel"
            ext in setOf("ppt", "pptx", "odp", "keynote") -> "powerpoint"
            ext in setOf("txt", "log", "csv", "tsv", "ini", "cfg", "conf", "properties", "prop", "nfo", "sfv", "ics", "vcf", "m3u", "m3u8", "cue", "patch", "diff") -> "txt"
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
            ext in setOf("sh", "bash", "zsh", "fish", "bat", "cmd", "ps1", "vbs") -> "shell"
            ext == "sql" -> "sql"
            ext in setOf("lua", "luac") -> "lua"
            ext == "exe" -> "exe"
            ext == "dll" -> "dll"
            ext == "so" -> "so"
            ext in setOf("bin", "elf", "msi", "appimage", "wasm", "class") -> "bin"
            ext in setOf("db", "realm", "mdb", "accdb") -> "db"
            ext in setOf("sqlite", "sqlite3") -> "sqlite"
            ext == "iso" -> "iso"
            ext in setOf("img", "simg", "raw", "dmg") -> "img"
            ext in setOf("ttf", "otf", "woff", "woff2") -> "font"
            ext in setOf("cer", "crt", "der", "p12", "pfx", "jks", "bks", "pem") -> "certificate"
            ext in setOf("key", "keystore", "pub") -> "key"
            ext in setOf("srt", "ass", "ssa", "vtt", "sub", "smi") -> "subtitle"
            ext == "torrent" -> "torrent"
            ext in setOf("bak", "backup", "old", "orig") -> "backup"
            ext in setOf("deb", "rpm", "ipk", "pkg") -> "linuxpkg"
            ext in setOf("obj", "fbx", "stl", "gltf", "glb", "3ds", "blend", "dae", "ply") -> "model3d"
            ext == "svg" -> "img"
            kind == FileKind.TEXT -> "txt"
            kind == FileKind.VIDEO -> null
            else -> null
        }
    }

    private const val EXTRA_ASSET_ZIP = "forge_extra_format_icons_v4.zip"
}
