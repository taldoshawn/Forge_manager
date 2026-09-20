package com.forgemanager.app.features.explorer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/**
 * Rich per-format icons inspired by the visual atlas supplied for Forge Manager.
 * They are rendered locally so every supported extension has an icon without
 * bundling dozens of independent bitmap resources.
 */
object FileIconAtlas {
    fun drawable(context: Context, name: String, isDirectory: Boolean, kind: FileKind): Drawable? {
        val key = keyFor(name, isDirectory, kind) ?: return null
        return FormatDrawable(key, context.resources.displayMetrics.density)
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

    private class FormatDrawable(private val key: String, private val density: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            if (key == "folder") drawFolder(canvas, w, h) else drawFile(canvas, w, h)
            canvas.restore()
        }

        private fun drawFolder(canvas: Canvas, w: Float, h: Float) {
            val r = RectF(w * .06f, h * .24f, w * .94f, h * .86f)
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(0f, r.top, 0f, r.bottom, Color.rgb(255, 202, 32), Color.rgb(238, 153, 12), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(r, w * .09f, w * .09f, paint)
            val tab = RectF(w * .10f, h * .13f, w * .51f, h * .37f)
            canvas.drawRoundRect(tab, w * .07f, w * .07f, paint)
            paint.shader = null
            paint.color = Color.argb(70, 255, 255, 255)
            paint.strokeWidth = maxOf(1f, density)
            canvas.drawLine(w * .12f, h * .31f, w * .88f, h * .31f, paint)
        }

        private fun drawFile(canvas: Canvas, w: Float, h: Float) {
            val base = colorFor(key)
            val dark = darken(base, .72f)
            val left = w * .10f
            val top = h * .05f
            val right = w * .90f
            val bottom = h * .95f
            val fold = w * .23f

            path.reset()
            path.moveTo(left + w * .07f, top)
            path.lineTo(right - fold, top)
            path.lineTo(right, top + fold)
            path.lineTo(right, bottom - w * .07f)
            path.quadTo(right, bottom, right - w * .07f, bottom)
            path.lineTo(left + w * .07f, bottom)
            path.quadTo(left, bottom, left, bottom - w * .07f)
            path.lineTo(left, top + w * .07f)
            path.quadTo(left, top, left + w * .07f, top)
            path.close()

            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(left, top, right, bottom, base, dark, Shader.TileMode.CLAMP)
            canvas.drawPath(path, paint)
            paint.shader = null

            path.reset()
            path.moveTo(right - fold, top)
            path.lineTo(right - fold, top + fold)
            path.lineTo(right, top + fold)
            path.close()
            paint.color = lighten(base, 1.23f)
            canvas.drawPath(path, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = maxOf(1f, density * .75f)
            paint.color = Color.argb(80, 255, 255, 255)
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL

            val label = labelFor(key)
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = when {
                label.length <= 2 -> w * .34f
                label.length <= 4 -> w * .23f
                else -> w * .17f
            }
            paint.color = textColorFor(key)
            val metrics = paint.fontMetrics
            val cy = h * .58f
            val y = cy - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(label, w * .50f, y, paint)

            if (key in setOf("db", "sqlite")) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = w * .035f
                paint.color = Color.argb(210, 230, 246, 255)
                val rr = RectF(w * .27f, h * .34f, w * .73f, h * .70f)
                canvas.drawOval(RectF(rr.left, rr.top, rr.right, rr.top + h * .12f), paint)
                canvas.drawLine(rr.left, rr.top + h * .06f, rr.left, rr.bottom, paint)
                canvas.drawLine(rr.right, rr.top + h * .06f, rr.right, rr.bottom, paint)
                canvas.drawArc(RectF(rr.left, rr.bottom - h * .12f, rr.right, rr.bottom), 0f, 180f, false, paint)
                paint.style = Paint.Style.FILL
            }
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        private fun labelFor(key: String): String = when (key) {
            "zip" -> "ZIP"; "rar" -> "RAR"; "7z" -> "7Z"; "tar" -> "TAR"; "gz" -> "GZ"
            "apk" -> "APK"; "aab" -> "AAB"; "xapb" -> "XAPB"; "xapk" -> "XAPK"; "dex" -> "DEX"
            "smali" -> "</>"; "xml" -> "XML"; "arsc" -> "ARSC"; "pdf" -> "PDF"
            "word" -> "W"; "excel" -> "X"; "powerpoint" -> "P"; "txt" -> "TXT"; "markdown" -> "M↓"; "json" -> "{}"
            "yaml" -> "YML"; "toml" -> "⚙"; "html" -> "</>"; "css" -> "CSS"; "javascript" -> "JS"; "typescript" -> "TS"
            "java" -> "J"; "kotlin" -> "K"; "python" -> "Py"; "c" -> "C"; "cpp" -> "C++"; "csharp" -> "C#"
            "rust" -> "R"; "go" -> "GO"; "php" -> "PHP"; "ruby" -> "Rb"; "swift" -> "S"; "dart" -> "D"
            "shell" -> ">_"; "sql" -> "SQL"; "lua" -> "Lua"; "jar" -> "JAR"; "exe" -> "EXE"; "dll" -> "DLL"; "so" -> "SO"
            "bin" -> "0101"; "db" -> ""; "sqlite" -> ""; "iso" -> "ISO"; "img" -> "IMG"; "font" -> "Aa"
            "certificate" -> "✓"; "key" -> "◆"; "epub" -> "EPUB"; "subtitle" -> "SRT"; "torrent" -> "µ"
            "backup" -> "↻"; "linuxpkg" -> "LIN"; "model3d" -> "3D"; else -> key.uppercase().take(5)
        }

        private fun colorFor(key: String): Int = when (key) {
            "zip", "7z", "gz", "dex", "smali", "exe", "dll", "so", "bin", "shell", "model3d" -> Color.rgb(50, 60, 74)
            "rar", "arsc", "csharp" -> Color.rgb(128, 53, 222)
            "tar" -> Color.rgb(188, 137, 80)
            "apk", "xapb", "xapk", "excel", "epub", "torrent" -> Color.rgb(38, 185, 78)
            "aab", "word", "css", "typescript", "c", "cpp", "dart", "db", "sqlite", "backup" -> Color.rgb(30, 119, 230)
            "pdf", "yaml", "ruby" -> Color.rgb(222, 48, 58)
            "powerpoint", "html", "swift", "linuxpkg" -> Color.rgb(245, 91, 35)
            "javascript" -> Color.rgb(249, 209, 38)
            "kotlin" -> Color.rgb(40, 47, 61)
            "python" -> Color.rgb(44, 139, 205)
            "rust" -> Color.rgb(45, 51, 61)
            "go" -> Color.rgb(40, 184, 221)
            "php" -> Color.rgb(150, 158, 180)
            "lua" -> Color.rgb(13, 34, 161)
            "font", "xml", "txt", "certificate" -> Color.rgb(232, 235, 240)
            "key" -> Color.rgb(55, 65, 78)
            "subtitle" -> Color.rgb(51, 60, 73)
            "iso", "img" -> Color.rgb(171, 178, 189)
            else -> Color.rgb(52, 109, 210)
        }

        private fun textColorFor(key: String): Int = when (key) {
            "javascript", "font", "xml", "txt", "certificate", "tar", "iso", "img" -> Color.rgb(25, 29, 35)
            else -> Color.WHITE
        }

        private fun darken(color: Int, factor: Float): Int = Color.rgb(
            (Color.red(color) * factor).toInt().coerceIn(0, 255),
            (Color.green(color) * factor).toInt().coerceIn(0, 255),
            (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        )

        private fun lighten(color: Int, factor: Float): Int = Color.rgb(
            (Color.red(color) * factor).toInt().coerceIn(0, 255),
            (Color.green(color) * factor).toInt().coerceIn(0, 255),
            (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        )
    }
}
