package com.forgemanager.app.features.explorer

enum class FileKind {
    DIRECTORY,
    APK,
    ARCHIVE,
    IMAGE,
    VIDEO,
    AUDIO,
    CODE,
    TEXT,
    PDF,
    DEX,
    XML,
    DATABASE,
    FONT,
    SPREADSHEET,
    PRESENTATION,
    CONFIG,
    CERTIFICATE,
    EXECUTABLE,
    GENERIC
}

object FileTypeClassifier {
    private val archives = setOf("zip", "jar", "aar", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst", "epub")
    private val images = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif", "avif", "svg", "ico")
    private val videos = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp", "ts", "m2ts", "flv")
    private val audio = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "amr", "mid", "midi")
    private val code = setOf(
        "kt", "kts", "java", "smali", "js", "mjs", "cjs", "ts", "tsx", "jsx", "py", "rb", "php", "go", "rs",
        "c", "cc", "cpp", "cxx", "h", "hpp", "cs", "swift", "dart", "lua", "sh", "bash", "zsh", "fish", "sql"
    )
    private val text = setOf("txt", "md", "markdown", "log", "csv", "ini", "cfg", "conf", "properties", "prop", "gradle")
    private val xml = setOf("xml", "xhtml", "html", "htm", "css")
    private val databases = setOf("db", "sqlite", "sqlite3", "realm", "mdb", "accdb")
    private val fonts = setOf("ttf", "otf", "woff", "woff2")
    private val spreadsheets = setOf("xls", "xlsx", "ods", "numbers")
    private val presentations = setOf("ppt", "pptx", "odp", "key")
    private val configs = setOf("json", "json5", "yaml", "yml", "toml", "env", "editorconfig")
    private val certificates = setOf("pem", "cer", "crt", "der", "p12", "pfx", "jks", "bks", "keystore")
    private val executables = setOf("so", "elf", "bin", "exe", "msi", "bat", "cmd")

    fun classify(name: String, isDirectory: Boolean): FileKind {
        if (isDirectory) return FileKind.DIRECTORY
        val ext = extensionOf(name)
        return when {
            ext == "apk" || ext == "apks" || ext == "xapk" || ext == "apkm" || ext == "aab" -> FileKind.APK
            ext == "dex" || ext == "vdex" || ext == "odex" -> FileKind.DEX
            ext == "pdf" -> FileKind.PDF
            ext in archives -> FileKind.ARCHIVE
            ext in images -> FileKind.IMAGE
            ext in videos -> FileKind.VIDEO
            ext in audio -> FileKind.AUDIO
            ext in code -> FileKind.CODE
            ext in xml -> FileKind.XML
            ext in databases -> FileKind.DATABASE
            ext in fonts -> FileKind.FONT
            ext in spreadsheets -> FileKind.SPREADSHEET
            ext in presentations -> FileKind.PRESENTATION
            ext in configs -> FileKind.CONFIG
            ext in certificates -> FileKind.CERTIFICATE
            ext in executables -> FileKind.EXECUTABLE
            ext in text || name.equals("README", true) || name.equals("LICENSE", true) || name.equals("NOTICE", true) -> FileKind.TEXT
            else -> FileKind.GENERIC
        }
    }

    fun extensionOf(name: String): String {
        if (name.startsWith('.') && name.count { it == '.' } == 1) return ""
        return name.substringAfterLast('.', "").lowercase()
    }

    fun shortLabel(kind: FileKind, name: String): String = when (kind) {
        FileKind.DIRECTORY -> "Pasta"
        FileKind.APK -> "APK"
        FileKind.ARCHIVE -> extensionOf(name).uppercase().ifBlank { "Arquivo" }
        FileKind.IMAGE -> "Imagem"
        FileKind.VIDEO -> "Vídeo"
        FileKind.AUDIO -> "Áudio"
        FileKind.CODE -> extensionOf(name).uppercase().ifBlank { "Código" }
        FileKind.TEXT -> "Texto"
        FileKind.PDF -> "PDF"
        FileKind.DEX -> "DEX"
        FileKind.XML -> extensionOf(name).uppercase().ifBlank { "XML" }
        FileKind.DATABASE -> "Banco"
        FileKind.FONT -> "Fonte"
        FileKind.SPREADSHEET -> "Planilha"
        FileKind.PRESENTATION -> "Apresentação"
        FileKind.CONFIG -> "Config"
        FileKind.CERTIFICATE -> "Certificado"
        FileKind.EXECUTABLE -> "Binário"
        FileKind.GENERIC -> extensionOf(name).uppercase().ifBlank { "Arquivo" }
    }
}
