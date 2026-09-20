package com.forgemanager.app.features.editor

import java.util.Locale

enum class EditorLanguage {
    PLAIN, XML, JSON, YAML, TOML, PYTHON, JAVA, KOTLIN, SMALI, HTML, MARKDOWN, CSS,
    JAVASCRIPT, TYPESCRIPT, C, CPP, CSHARP, RUST, GO, PHP, RUBY, SWIFT, DART, SHELL, SQL, LUA
}

data class EditorProfile(
    val language: EditorLanguage,
    val label: String,
    val commentPrefix: String?,
    val symbolBar: List<String>,
    val canFormat: Boolean = true,
    val canValidate: Boolean = false,
    val canOutline: Boolean = true,
    val canPreview: Boolean = false,
    val indentationAfterColon: Boolean = false
) {
    companion object {
        fun forLanguage(language: EditorLanguage): EditorProfile = forFile("file.${extensionFor(language)}")

        fun extensionFor(language: EditorLanguage): String = when (language) {
            EditorLanguage.PLAIN -> "txt"
            EditorLanguage.XML -> "xml"
            EditorLanguage.JSON -> "json"
            EditorLanguage.YAML -> "yaml"
            EditorLanguage.TOML -> "toml"
            EditorLanguage.PYTHON -> "py"
            EditorLanguage.JAVA -> "java"
            EditorLanguage.KOTLIN -> "kt"
            EditorLanguage.SMALI -> "smali"
            EditorLanguage.HTML -> "html"
            EditorLanguage.MARKDOWN -> "md"
            EditorLanguage.CSS -> "css"
            EditorLanguage.JAVASCRIPT -> "js"
            EditorLanguage.TYPESCRIPT -> "ts"
            EditorLanguage.C -> "c"
            EditorLanguage.CPP -> "cpp"
            EditorLanguage.CSHARP -> "cs"
            EditorLanguage.RUST -> "rs"
            EditorLanguage.GO -> "go"
            EditorLanguage.PHP -> "php"
            EditorLanguage.RUBY -> "rb"
            EditorLanguage.SWIFT -> "swift"
            EditorLanguage.DART -> "dart"
            EditorLanguage.SHELL -> "sh"
            EditorLanguage.SQL -> "sql"
            EditorLanguage.LUA -> "lua"
        }

        fun forFile(name: String): EditorProfile {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val common = listOf("TAB", "(", ")", "[", "]", "{", "}", "=", "\"")
            return when (ext) {
                "xml", "xsl", "xslt", "plist" -> EditorProfile(EditorLanguage.XML, "XML", "<!-- -->", listOf("<", ">", "/", "=", "\"", "'"), canValidate = true)
                "json", "json5" -> EditorProfile(EditorLanguage.JSON, "JSON", null, listOf("{", "}", "[", "]", ":", ",", "\""), canValidate = true)
                "yaml", "yml" -> EditorProfile(EditorLanguage.YAML, "YAML", "#", listOf("TAB", ":", "-", "[", "]", "{", "}", "|", ">", "\""), canValidate = true)
                "toml" -> EditorProfile(EditorLanguage.TOML, "TOML", "#", listOf("[", "]", "=", "\"", "'", ","), canValidate = true)
                "py" -> EditorProfile(EditorLanguage.PYTHON, "Python", "#", listOf("TAB", ":", "(", ")", "[", "]", "{", "}", "_", "=", "\"", "'"), indentationAfterColon = true)
                "java" -> EditorProfile(EditorLanguage.JAVA, "Java", "//", listOf("TAB", ";", "{", "}", "(", ")", "[", "]", ".", "=", "\""))
                "kt", "kts" -> EditorProfile(EditorLanguage.KOTLIN, "Kotlin", "//", listOf("TAB", ";", "{", "}", "(", ")", "[", "]", ".", "=", "\""))
                "smali" -> EditorProfile(EditorLanguage.SMALI, "Smali", "#", listOf("TAB", ".", ":", "/", ";", "(", ")", "v", "p"), canValidate = true)
                "html", "htm", "xhtml" -> EditorProfile(EditorLanguage.HTML, "HTML", "<!-- -->", listOf("<", ">", "/", "=", "\"", "'"), canPreview = true)
                "md", "markdown", "mdown", "mkd" -> EditorProfile(EditorLanguage.MARKDOWN, "Markdown", null, listOf("#", "**", "_", "[]()", "- ", "- [ ] ", "> ", "`", "```"), canPreview = true)
                "css", "scss", "sass", "less" -> EditorProfile(EditorLanguage.CSS, "CSS", "/* */", listOf("{", "}", ":", ";", ".", "#", "(", ")", "%"))
                "js", "mjs", "cjs", "jsx" -> EditorProfile(EditorLanguage.JAVASCRIPT, "JavaScript", "//", common + listOf(";", ".", "=>"))
                "ts", "tsx" -> EditorProfile(EditorLanguage.TYPESCRIPT, "TypeScript", "//", common + listOf(";", ".", "=>", ":"))
                "c", "h" -> EditorProfile(EditorLanguage.C, "C", "//", common + listOf(";", "#include"))
                "cc", "cpp", "cxx", "hpp" -> EditorProfile(EditorLanguage.CPP, "C++", "//", common + listOf(";", "::", "#include"))
                "cs" -> EditorProfile(EditorLanguage.CSHARP, "C#", "//", common + listOf(";", "."))
                "rs" -> EditorProfile(EditorLanguage.RUST, "Rust", "//", common + listOf(";", "::", "->", "&"))
                "go" -> EditorProfile(EditorLanguage.GO, "Go", "//", common + listOf(";", ":="))
                "php" -> EditorProfile(EditorLanguage.PHP, "PHP", "//", common + listOf(";", "$", "->"))
                "rb" -> EditorProfile(EditorLanguage.RUBY, "Ruby", "#", listOf("TAB", "(", ")", "[", "]", "{", "}", ":", "=", "\"", "'"))
                "swift" -> EditorProfile(EditorLanguage.SWIFT, "Swift", "//", common + listOf(";", "."))
                "dart" -> EditorProfile(EditorLanguage.DART, "Dart", "//", common + listOf(";", "."))
                "sh", "bash", "zsh", "fish" -> EditorProfile(EditorLanguage.SHELL, "Shell", "#", listOf("TAB", "$", "|", "&", ">", "<", "/", "\"", "'"))
                "sql" -> EditorProfile(EditorLanguage.SQL, "SQL", "--", listOf("(", ")", ",", ";", "=", "'", "\"", "."))
                "lua" -> EditorProfile(EditorLanguage.LUA, "Lua", "--", listOf("TAB", "(", ")", "[", "]", "{", "}", "=", "\"", "'"))
                else -> EditorProfile(EditorLanguage.PLAIN, "Texto", null, listOf("TAB", "(", ")", "[", "]", "{", "}", "\"", "'"), canFormat = false, canOutline = false)
            }
        }
    }
}
