package com.forgemanager.app.features.editor

import android.graphics.Color
import android.text.Editable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance

object SyntaxHighlighter {
    private const val MAX_HIGHLIGHT_CHARS = 1_500_000
    private const val WINDOW_AROUND_CHANGE = 8_000

    private val strings = Regex("(?s)(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')")
    private val numbers = Regex("\\b(?:0[xX][0-9A-Fa-f]+|0[bB][01]+|\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\b")
    private val operators = Regex("(?:===|!==|==|!=|<=|>=|=>|->|::|&&|\\|\\||\\+\\+|--|[+*/%=&|!<>?:~-])")
    private val annotations = Regex("@[A-Za-z_][\\w.]*")
    private val typeNames = Regex("\\b[A-Z][A-Za-z0-9_$]{2,}\\b")
    private val functionNames = Regex("\\b[A-Za-z_$][\\w$]*(?=\\s*\\()")
    private val htmlTagName = Regex("(?<=</?)[A-Za-z_][\\w:.-]*")
    private val htmlAttribute = Regex("\\b[A-Za-z_:][\\w:.-]*(?=\\s*=)")
    private val markdownHeading = Regex("(?m)^#{1,6}\\s+.+$")
    private val markdownLink = Regex("!?\\[[^]\\n]+](?:\\([^)]*\\)|\\[[^]]*])")
    private val markdownCode = Regex("(?s)```.*?```|`[^`\\n]+`")
    private val smaliDirective = Regex("(?m)^\\s*\\.(?:class|super|implements|field|method|end\\s+method|locals|registers|annotation|end\\s+annotation|line|param|prologue|source)\\b[^\\n]*")
    private val smaliRegister = Regex("\\b[vp]\\d+\\b")
    private val smaliLabel = Regex("(?m)^\\s*:[A-Za-z0-9_.$-]+")
    private val cssSelector = Regex("(?m)(?<![;{}])(?:^|})\\s*([^@{}][^{}]*?)(?=\\s*\\{)")
    private val cssProperty = Regex("(?m)\\b[-A-Za-z][\\w-]*(?=\\s*:)")
    private val hexColor = Regex("(?<![A-Za-z0-9])#[0-9A-Fa-f]{3,8}\\b")
    private val colorFunction = Regex("\\b(?:rgb|rgba|hsl|hsla)\\([^)]*\\)", RegexOption.IGNORE_CASE)

    private val keywords = mapOf(
        EditorLanguage.PYTHON to setOf("and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif", "else", "except", "False", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "return", "True", "try", "while", "with", "yield"),
        EditorLanguage.JAVA to setOf("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected", "public", "record", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "var", "void", "volatile", "while", "true", "false", "null"),
        EditorLanguage.KOTLIN to setOf("as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while", "by", "catch", "constructor", "delegate", "dynamic", "field", "file", "finally", "get", "import", "init", "param", "property", "receiver", "set", "setparam", "where", "actual", "abstract", "annotation", "companion", "const", "crossinline", "data", "enum", "expect", "external", "final", "infix", "inline", "inner", "internal", "lateinit", "noinline", "open", "operator", "out", "override", "private", "protected", "public", "reified", "sealed", "suspend", "tailrec", "vararg"),
        EditorLanguage.JAVASCRIPT to setOf("async", "await", "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do", "else", "export", "extends", "false", "finally", "for", "from", "function", "get", "if", "import", "in", "instanceof", "let", "new", "null", "of", "return", "set", "static", "super", "switch", "this", "throw", "true", "try", "typeof", "undefined", "var", "void", "while", "with", "yield"),
        EditorLanguage.TYPESCRIPT to setOf("abstract", "any", "as", "asserts", "async", "await", "boolean", "break", "case", "catch", "class", "const", "constructor", "continue", "declare", "default", "delete", "do", "else", "enum", "export", "extends", "false", "finally", "for", "from", "function", "get", "if", "implements", "import", "in", "infer", "instanceof", "interface", "keyof", "let", "module", "namespace", "never", "new", "null", "number", "object", "of", "private", "protected", "public", "readonly", "return", "set", "static", "string", "super", "switch", "symbol", "this", "throw", "true", "try", "type", "typeof", "undefined", "unknown", "var", "void", "while", "yield"),
        EditorLanguage.RUST to setOf("as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum", "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return", "self", "Self", "static", "struct", "super", "trait", "true", "type", "union", "unsafe", "use", "where", "while"),
        EditorLanguage.GO to setOf("break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough", "for", "func", "go", "goto", "if", "import", "interface", "map", "package", "range", "return", "select", "struct", "switch", "type", "var"),
        EditorLanguage.SQL to setOf("SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "TABLE", "VIEW", "INDEX", "DROP", "ALTER", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON", "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "OFFSET", "UNION", "ALL", "DISTINCT", "AS", "AND", "OR", "NOT", "NULL", "IS", "IN", "EXISTS", "CASE", "WHEN", "THEN", "ELSE", "END", "WITH", "PRIMARY", "KEY", "FOREIGN", "REFERENCES"),
        EditorLanguage.SHELL to setOf("if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac", "function", "in", "select", "until", "time", "coproc"),
        EditorLanguage.LUA to setOf("and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if", "in", "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while")
    )

    private val genericKeywords = setOf(
        "class", "interface", "object", "struct", "enum", "trait", "impl", "fun", "func", "fn", "function", "def",
        "val", "var", "let", "const", "return", "if", "else", "for", "while", "do", "switch", "case", "break", "continue",
        "try", "catch", "finally", "throw", "new", "public", "private", "protected", "internal", "static", "final", "import",
        "package", "extends", "implements", "override", "abstract", "async", "await", "true", "false", "null"
    )

    fun apply(editable: Editable, fileName: String) {
        editable.getSpans(0, editable.length, SyntaxSpan::class.java).forEach(editable::removeSpan)
        if (editable.isEmpty() || editable.length > MAX_HIGHLIGHT_CHARS) return
        applyWindow(editable, fileName, 0, editable.length)
    }

    fun applyChanged(editable: Editable, fileName: String, changedStart: Int, changedEnd: Int) {
        if (editable.isEmpty() || editable.length > MAX_HIGHLIGHT_CHARS) return
        var start = (changedStart - WINDOW_AROUND_CHANGE).coerceAtLeast(0)
        var end = (changedEnd + WINDOW_AROUND_CHANGE).coerceAtMost(editable.length)
        while (start > 0 && editable[start - 1] != '\n') start--
        while (end < editable.length && editable[end] != '\n') end++
        editable.getSpans(start, end, SyntaxSpan::class.java).forEach(editable::removeSpan)
        applyWindow(editable, fileName, start, end)
    }

    private fun applyWindow(editable: Editable, fileName: String, start: Int, end: Int) {
        if (start >= end) return
        val profile = EditorProfile.forFile(fileName)
        val text = editable.subSequence(start, end).toString()
        fun paint(regex: Regex, color: Int, bold: Boolean = false, bg: Int? = null) {
            regex.findAll(text).forEach { match ->
                editable.setSpan(
                    SyntaxSpan(fg = color, bg = bg, bold = bold),
                    start + match.range.first,
                    start + match.range.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        if (profile.language == EditorLanguage.MARKDOWN) {
            paint(markdownHeading, Color.rgb(96, 165, 250), true)
            paint(markdownLink, Color.rgb(192, 132, 252))
            paint(markdownCode, Color.rgb(134, 239, 172))
        }

        if (profile.language in setOf(EditorLanguage.XML, EditorLanguage.HTML)) {
            paint(htmlTagName, Color.rgb(192, 132, 252), true)
            paint(htmlAttribute, Color.rgb(96, 165, 250))
            paint(Regex("(?s)<!--.*?-->"), Color.rgb(113, 128, 150))
        }

        if (profile.language == EditorLanguage.SMALI) {
            paint(smaliDirective, Color.rgb(96, 165, 250), true)
            paint(smaliRegister, Color.rgb(251, 191, 36))
            paint(smaliLabel, Color.rgb(192, 132, 252))
        }

        if (profile.language == EditorLanguage.CSS) {
            paint(cssSelector, Color.rgb(192, 132, 252))
            paint(cssProperty, Color.rgb(96, 165, 250))
        }

        if (profile.language != EditorLanguage.PLAIN && profile.language != EditorLanguage.MARKDOWN) {
            commentRegexes(profile.language).forEach { paint(it, Color.rgb(113, 128, 150)) }
            paint(strings, Color.rgb(134, 239, 172))
            val words = keywords[profile.language] ?: genericKeywords
            if (words.isNotEmpty()) {
                val opts = if (profile.language == EditorLanguage.SQL) setOf(RegexOption.IGNORE_CASE) else emptySet()
                paint(Regex("\\b(?:${words.joinToString("|") { Regex.escape(it) }})\\b", opts), Color.rgb(96, 165, 250), true)
            }
            paint(numbers, Color.rgb(251, 191, 36))
            if (profile.language !in setOf(EditorLanguage.XML, EditorLanguage.HTML, EditorLanguage.JSON, EditorLanguage.YAML, EditorLanguage.TOML, EditorLanguage.SMALI)) {
                paint(annotations, Color.rgb(251, 146, 60))
                paint(typeNames, Color.rgb(103, 232, 249))
                paint(functionNames, Color.rgb(196, 181, 253))
                paint(operators, Color.rgb(148, 163, 184))
            }
        }

        if (profile.language in setOf(EditorLanguage.CSS, EditorLanguage.HTML, EditorLanguage.MARKDOWN, EditorLanguage.PLAIN)) {
            hexColor.findAll(text).forEach { match ->
                parseHexColor(match.value)?.let { color ->
                    val foreground = if (luminance(color) > 0.55) Color.rgb(20, 23, 28) else Color.WHITE
                    editable.setSpan(
                        SyntaxSpan(fg = foreground, bg = color, bold = true),
                        start + match.range.first,
                        start + match.range.last + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            paint(colorFunction, Color.rgb(251, 191, 36))
        }
    }

    private fun commentRegexes(language: EditorLanguage): List<Regex> = when (language) {
        EditorLanguage.PYTHON, EditorLanguage.YAML, EditorLanguage.TOML, EditorLanguage.SHELL, EditorLanguage.SMALI ->
            listOf(Regex("(?m)#.*$"))
        EditorLanguage.SQL, EditorLanguage.LUA -> listOf(Regex("(?m)--.*$"), Regex("(?s)/\\*.*?\\*/"))
        EditorLanguage.XML, EditorLanguage.HTML -> emptyList()
        else -> listOf(Regex("(?m)//.*$"), Regex("(?s)/\\*.*?\\*/"))
    }

    private fun parseHexColor(raw: String): Int? = runCatching {
        val value = when (raw.length) {
            4 -> "#" + raw.drop(1).joinToString("") { "$it$it" }
            5 -> "#" + raw.drop(1).joinToString("") { "$it$it" }
            else -> raw
        }
        Color.parseColor(value)
    }.getOrNull()

    private fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val v = value / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(Color.red(color)) + 0.7152 * channel(Color.green(color)) + 0.0722 * channel(Color.blue(color))
    }

    private class SyntaxSpan(
        private val fg: Int? = null,
        private val bg: Int? = null,
        private val bold: Boolean = false
    ) : CharacterStyle(), UpdateAppearance {
        override fun updateDrawState(tp: TextPaint) {
            fg?.let { tp.color = it }
            bg?.let { tp.bgColor = it }
            if (bold) tp.isFakeBoldText = true
        }
    }
}
