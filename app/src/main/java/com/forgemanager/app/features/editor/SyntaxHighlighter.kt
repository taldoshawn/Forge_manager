package com.forgemanager.app.features.editor

import android.graphics.Color
import android.text.Editable
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import java.util.Locale

object SyntaxHighlighter {
    private const val MAX_HIGHLIGHT_CHARS = 1_500_000
    private val hexColor = Regex("(?<![A-Za-z0-9])#[0-9A-Fa-f]{3,8}\\b")
    private val strings = Regex("(?s)(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')")
    private val lineComments = Regex("(?m)(//.*$|#(?![0-9A-Fa-f]{3,8}\\b).*$)")
    private val blockComments = Regex("(?s)/\\*.*?\\*/")
    private val numbers = Regex("\\b(?:0x[0-9A-Fa-f]+|\\d+(?:\\.\\d+)?)\\b")
    private val htmlTags = Regex("</?[A-Za-z][^>]*>")
    private val markdownHeading = Regex("(?m)^#{1,6}\\s+.+$")
    private val markdownLink = Regex("!?\\[[^]]+](?:\\([^)]*\\)|\\[[^]]*])")
    private val markdownCode = Regex("(?s)```.*?```|`[^`\\n]+`")

    private val commonKeywords = setOf(
        "class", "interface", "object", "fun", "val", "var", "return", "if", "else", "when", "for", "while", "do",
        "try", "catch", "finally", "throw", "new", "public", "private", "protected", "internal", "static", "final", "const",
        "import", "package", "extends", "implements", "override", "abstract", "async", "await", "function", "let", "const",
        "def", "lambda", "yield", "from", "as", "in", "is", "not", "and", "or", "true", "false", "null", "None",
        "struct", "enum", "trait", "impl", "fn", "mut", "pub", "use", "mod", "match", "switch", "case", "break", "continue"
    )
    private val keywordRegex = Regex("\\b(?:${commonKeywords.joinToString("|") { Regex.escape(it) }})\\b")

    fun apply(editable: Editable, fileName: String) {
        editable.getSpans(0, editable.length, SyntaxSpan::class.java).forEach(editable::removeSpan)
        if (editable.length == 0 || editable.length > MAX_HIGHLIGHT_CHARS) return

        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val isMarkdown = ext == "md" || ext == "markdown"
        val isHtml = ext in setOf("html", "htm", "xhtml", "svg", "xml")
        val codeLike = ext in setOf(
            "kt", "kts", "java", "smali", "js", "mjs", "cjs", "ts", "tsx", "jsx", "py", "rb", "php", "go", "rs",
            "c", "cc", "cpp", "cxx", "h", "hpp", "cs", "swift", "dart", "lua", "sh", "bash", "zsh", "fish", "sql",
            "json", "json5", "yaml", "yml", "toml", "css", "gradle"
        )

        if (isMarkdown) {
            paint(editable, markdownHeading, fg = Color.rgb(96, 165, 250), bold = true)
            paint(editable, markdownLink, fg = Color.rgb(192, 132, 252))
            paint(editable, markdownCode, fg = Color.rgb(134, 239, 172))
        }
        if (isHtml) {
            paint(editable, htmlTags, fg = Color.rgb(192, 132, 252))
        }
        if (codeLike || isHtml) {
            paint(editable, blockComments, fg = Color.rgb(113, 128, 150))
            paint(editable, lineComments, fg = Color.rgb(113, 128, 150))
            paint(editable, strings, fg = Color.rgb(134, 239, 172))
            paint(editable, keywordRegex, fg = Color.rgb(96, 165, 250), bold = true)
            paint(editable, numbers, fg = Color.rgb(251, 191, 36))
        }

        // Hex colors are highlighted in code, CSS, Markdown and plain text so visual color tokens are obvious.
        hexColor.findAll(editable).forEach { match ->
            parseHexColor(match.value)?.let { color ->
                val foreground = if (luminance(color) > 0.55) Color.rgb(20, 23, 28) else Color.WHITE
                editable.setSpan(SyntaxSpan(fg = foreground, bg = color, bold = true), match.range.first, match.range.last + 1, Editable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun paint(editable: Editable, regex: Regex, fg: Int, bold: Boolean = false) {
        regex.findAll(editable).forEach { match ->
            editable.setSpan(SyntaxSpan(fg = fg, bold = bold), match.range.first, match.range.last + 1, Editable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun parseHexColor(raw: String): Int? = runCatching {
        val value = when (raw.length) {
            4 -> "#" + raw.drop(1).map { "$it$it" }.joinToString("")
            5 -> {
                val c = raw.drop(1)
                "#" + c.map { "$it$it" }.joinToString("")
            }
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
