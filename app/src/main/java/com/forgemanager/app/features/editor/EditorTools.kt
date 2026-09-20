package com.forgemanager.app.features.editor

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import java.io.StringReader
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

data class EditorDiagnostic(val ok: Boolean, val message: String, val line: Int? = null, val column: Int? = null)
data class EditorSymbol(val name: String, val kind: String, val line: Int)

object EditorTools {
    fun format(profile: EditorProfile, source: String): Result<String> = runCatching {
        when (profile.language) {
            EditorLanguage.JSON -> formatJson(source, pretty = true)
            EditorLanguage.XML -> formatXml(source, pretty = true)
            EditorLanguage.HTML -> formatHtml(source)
            EditorLanguage.CSS,
            EditorLanguage.JAVA, EditorLanguage.KOTLIN, EditorLanguage.JAVASCRIPT, EditorLanguage.TYPESCRIPT,
            EditorLanguage.C, EditorLanguage.CPP, EditorLanguage.CSHARP, EditorLanguage.RUST,
            EditorLanguage.GO, EditorLanguage.PHP, EditorLanguage.SWIFT, EditorLanguage.DART -> formatBraced(source)
            EditorLanguage.SQL -> formatSql(source)
            else -> source.lineSequence().joinToString("\n") { it.trimEnd() }
        }
    }

    fun minify(profile: EditorProfile, source: String): Result<String> = runCatching {
        when (profile.language) {
            EditorLanguage.JSON -> formatJson(source, pretty = false)
            EditorLanguage.XML -> formatXml(source, pretty = false)
            else -> error("Minificação não disponível para ${profile.label}")
        }
    }

    fun validate(profile: EditorProfile, source: String): EditorDiagnostic {
        return try {
            when (profile.language) {
                EditorLanguage.JSON -> {
                    parseJson(source)
                    EditorDiagnostic(true, "JSON válido")
                }
                EditorLanguage.XML -> {
                    secureDocumentBuilder().parse(InputSource(StringReader(source)))
                    EditorDiagnostic(true, "XML válido")
                }
                EditorLanguage.YAML -> validateYaml(source)
                EditorLanguage.TOML -> validateToml(source)
                EditorLanguage.SMALI -> validateSmali(source)
                else -> validateBrackets(source)
            }
        } catch (e: SAXParseException) {
            EditorDiagnostic(false, e.message ?: "XML inválido", e.lineNumber.takeIf { it > 0 }, e.columnNumber.takeIf { it > 0 })
        } catch (e: Throwable) {
            val (line, column) = jsonLineColumn(e.message.orEmpty())
            EditorDiagnostic(false, e.message ?: "Conteúdo inválido", line, column)
        }
    }

    fun symbols(profile: EditorProfile, source: String, limit: Int = 2_000): List<EditorSymbol> {
        val patterns = when (profile.language) {
            EditorLanguage.PYTHON -> listOf(
                "class" to Regex("(?m)^\\s*class\\s+([A-Za-z_][\\w]*)"),
                "função" to Regex("(?m)^\\s*(?:async\\s+)?def\\s+([A-Za-z_][\\w]*)\\s*\\(")
            )
            EditorLanguage.JAVA -> listOf(
                "tipo" to Regex("(?m)\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)"),
                "método" to Regex("(?m)^[ \\t]*(?:@[\\w$.]+[ \\t]*)*(?:[\\w$<>?\\[\\],.]+[ \\t]+)+([A-Za-z_$][\\w$]*)[ \\t]*\\([^;{}]*\\)[ \\t]*(?:throws[^\\{]+)?\\{")
            )
            EditorLanguage.KOTLIN -> listOf(
                "tipo" to Regex("(?m)\\b(?:class|interface|object|data\\s+class|enum\\s+class|sealed\\s+class)\\s+([A-Za-z_][\\w]*)"),
                "função" to Regex("(?m)\\bfun\\s+(?:<[^>]+>\\s*)?(?:[\\w?.<>]+\\.)?([A-Za-z_][\\w]*)\\s*\\(")
            )
            EditorLanguage.SMALI -> listOf(
                "classe" to Regex("(?m)^\\s*\\.class\\b[^\\n]*?\\s(L[^;]+;)"),
                "campo" to Regex("(?m)^\\s*\\.field\\b[^\\n]*?\\s([\\w$<>-]+):"),
                "método" to Regex("(?m)^\\s*\\.method\\b[^\\n]*?\\s([\\w$<>-]+)\\("),
                "label" to Regex("(?m)^\\s*:([\\w$.-]+)")
            )
            EditorLanguage.XML, EditorLanguage.HTML -> listOf(
                "tag" to Regex("<([A-Za-z_][\\w:.-]*)(?=\\s|/?>)")
            )
            EditorLanguage.JAVASCRIPT, EditorLanguage.TYPESCRIPT -> listOf(
                "classe" to Regex("(?m)\\bclass\\s+([A-Za-z_$][\\w$]*)"),
                "função" to Regex("(?m)\\b(?:async\\s+)?function\\s+([A-Za-z_$][\\w$]*)\\s*\\("),
                "função" to Regex("(?m)\\b(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s*)?\\([^)]*\\)\\s*=>")
            )
            EditorLanguage.C, EditorLanguage.CPP, EditorLanguage.CSHARP, EditorLanguage.RUST,
            EditorLanguage.GO, EditorLanguage.PHP, EditorLanguage.RUBY, EditorLanguage.SWIFT,
            EditorLanguage.DART, EditorLanguage.LUA -> listOf(
                "tipo" to Regex("(?m)\\b(?:class|struct|interface|enum|trait)\\s+([A-Za-z_][\\w]*)"),
                "função" to Regex("(?m)\\b(?:fun|func|fn|function|def)\\s+([A-Za-z_][\\w]*)\\s*\\(")
            )
            EditorLanguage.CSS -> listOf("seletor" to Regex("(?m)^\\s*([^@{}][^{}]*?)\\s*\\{"))
            EditorLanguage.SQL -> listOf("declaração" to Regex("(?im)\\b(?:CREATE\\s+(?:TABLE|VIEW|INDEX)|WITH)\\s+([A-Za-z_][\\w.]*)"))
            else -> emptyList()
        }
        if (patterns.isEmpty()) return emptyList()
        val starts = lineStarts(source)
        val out = ArrayList<EditorSymbol>()
        for ((kind, regex) in patterns) {
            for (match in regex.findAll(source)) {
                if (out.size >= limit) break
                val name = match.groups.getOrNull(1)?.value ?: match.value.trim()
                out += EditorSymbol(name, kind, lineAt(starts, match.range.first))
            }
        }
        return out.sortedWith(compareBy<EditorSymbol> { it.line }.thenBy { it.name })
    }

    fun structure(profile: EditorProfile, source: String): Result<String> = runCatching {
        when (profile.language) {
            EditorLanguage.JSON -> buildJsonTree(parseJson(source))
            EditorLanguage.XML -> buildXmlTree(source)
            else -> {
                val values = symbols(profile, source)
                if (values.isEmpty()) "Nenhum símbolo encontrado"
                else values.joinToString("\n") { "${it.line}: ${it.kind}  ${it.name}" }
            }
        }
    }

    private fun formatJson(source: String, pretty: Boolean): String {
        val value = parseJson(source)
        return when (value) {
            is JSONObject -> if (pretty) value.toString(2) else value.toString()
            is JSONArray -> if (pretty) value.toString(2) else value.toString()
            else -> error("JSON deve começar com objeto ou array")
        }
    }

    private fun parseJson(source: String): Any {
        val value = JSONTokener(source).nextValue()
        require(value is JSONObject || value is JSONArray) { "JSON deve começar com objeto ou array" }
        return value
    }

    private fun secureDocumentBuilder() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isXIncludeAware = false
        setExpandEntityReferences(false)
        runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }.newDocumentBuilder()

    private fun formatXml(source: String, pretty: Boolean): String {
        val document = secureDocumentBuilder().parse(InputSource(StringReader(source)))
        val factory = TransformerFactory.newInstance().apply {
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        }
        val transformer = factory.newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, if (source.trimStart().startsWith("<?xml")) "no" else "yes")
            setOutputProperty(OutputKeys.INDENT, if (pretty) "yes" else "no")
            if (pretty) runCatching { setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2") }
        }
        return StringWriter().also { transformer.transform(DOMSource(document), StreamResult(it)) }.toString().trimEnd()
    }

    private fun formatHtml(source: String): String {
        // Conservative line formatter: it never parses/executes scripts and does not rewrite tag contents.
        val tokens = Regex("(?s)(<!--.*?-->|<![^>]*>|<[^>]+>|[^<]+)").findAll(source).map { it.value }.toList()
        val out = StringBuilder()
        var depth = 0
        val voids = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
        for (raw in tokens) {
            val token = raw.trim()
            if (token.isEmpty()) continue
            val close = token.startsWith("</")
            val tagName = Regex("^</?\\s*([A-Za-z0-9:-]+)").find(token)?.groupValues?.getOrNull(1)?.lowercase()
            if (close) depth = (depth - 1).coerceAtLeast(0)
            token.lineSequence().forEach { line -> out.append("  ".repeat(depth)).append(line.trim()).append('\n') }
            val opens = token.startsWith("<") && !close && !token.startsWith("<!") && !token.endsWith("/>") && tagName !in voids
            if (opens && !token.contains("</")) depth++
        }
        return out.toString().trimEnd()
    }

    private fun formatBraced(source: String): String {
        val out = StringBuilder()
        var depth = 0
        for (raw in source.lines()) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) { out.append('\n'); continue }
            if (trimmed.startsWith("}")) depth = (depth - 1).coerceAtLeast(0)
            out.append("    ".repeat(depth)).append(trimmed).append('\n')
            val opens = countOutsideStrings(trimmed, '{')
            val closes = countOutsideStrings(trimmed, '}') - if (trimmed.startsWith("}")) 1 else 0
            depth = (depth + opens - closes).coerceAtLeast(0)
        }
        return out.toString().trimEnd()
    }

    private fun formatSql(source: String): String {
        val keywords = listOf("select", "from", "where", "group by", "order by", "having", "limit", "insert into", "values", "update", "set", "delete from", "join", "left join", "right join", "inner join", "on", "union", "create table")
        var value = source.lineSequence().joinToString(" ") { it.trim() }.replace(Regex("\\s+"), " ").trim()
        for (keyword in keywords.sortedByDescending(String::length)) {
            value = Regex("(?i)\\b${Regex.escape(keyword)}\\b").replace(value) { it.value.uppercase() }
        }
        val breaks = listOf("SELECT", "FROM", "WHERE", "GROUP BY", "ORDER BY", "HAVING", "LIMIT", "INSERT INTO", "UPDATE", "DELETE FROM", "LEFT JOIN", "RIGHT JOIN", "INNER JOIN", "JOIN", "UNION")
        for (keyword in breaks) value = value.replace(Regex("(?i)\\s+${Regex.escape(keyword)}\\b"), "\n$keyword")
        return value.trim()
    }

    private fun validateYaml(source: String): EditorDiagnostic {
        source.lines().forEachIndexed { index, line ->
            val leading = line.takeWhile { it == ' ' || it == '\t' }
            if ('\t' in leading) return EditorDiagnostic(false, "YAML não permite TAB na indentação", index + 1, leading.indexOf('\t') + 1)
        }
        return validateBrackets(source).let { if (it.ok) EditorDiagnostic(true, "Estrutura YAML básica válida") else it }
    }

    private fun validateToml(source: String): EditorDiagnostic {
        source.lines().forEachIndexed { index, line ->
            val value = line.substringBefore('#').trim()
            if (value.isEmpty()) return@forEachIndexed
            if (value.startsWith('[')) {
                if (!value.endsWith(']')) return EditorDiagnostic(false, "Cabeçalho TOML sem fechamento", index + 1, value.length)
            } else if ('=' !in value) {
                return EditorDiagnostic(false, "Entrada TOML deve usar chave = valor", index + 1, 1)
            }
        }
        return EditorDiagnostic(true, "Estrutura TOML básica válida")
    }

    private fun validateSmali(source: String): EditorDiagnostic {
        val lines = source.lines()
        val classLine = lines.indexOfFirst { it.trimStart().startsWith(".class ") }
        if (classLine < 0) return EditorDiagnostic(false, "Diretiva .class ausente")
        var openMethod = -1
        lines.forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.startsWith(".method ")) {
                if (openMethod >= 0) return EditorDiagnostic(false, "Método iniciado antes de fechar o anterior", index + 1, 1)
                openMethod = index
            } else if (line == ".end method") {
                if (openMethod < 0) return EditorDiagnostic(false, ".end method sem .method", index + 1, 1)
                openMethod = -1
            }
            if ((line.startsWith(".locals ") || line.startsWith(".registers ")) && line.substringAfter(' ').trim().toIntOrNull() == null) {
                return EditorDiagnostic(false, "Quantidade de registers inválida", index + 1, 1)
            }
        }
        if (openMethod >= 0) return EditorDiagnostic(false, "Método sem .end method", openMethod + 1, 1)
        return EditorDiagnostic(true, "Estrutura Smali válida")
    }

    private fun validateBrackets(source: String): EditorDiagnostic {
        val stack = ArrayDeque<Pair<Char, Int>>()
        var quote: Char? = null
        var escaped = false
        source.forEachIndexed { index, char ->
            if (quote != null) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == quote) quote = null
                return@forEachIndexed
            }
            if (char == '\'' || char == '"') { quote = char; return@forEachIndexed }
            when (char) {
                '(', '[', '{' -> stack.addLast(char to index)
                ')', ']', '}' -> {
                    val expected = when (char) { ')' -> '('; ']' -> '['; else -> '{' }
                    val last = if (stack.isEmpty()) null else stack.removeLast()
                    if (last?.first != expected) {
                        val starts = lineStarts(source)
                        return EditorDiagnostic(false, "Fechamento '$char' sem abertura correspondente", lineAt(starts, index), null)
                    }
                }
            }
        }
        if (quote != null) return EditorDiagnostic(false, "String sem fechamento")
        if (stack.isNotEmpty()) {
            val last = stack.last()
            val starts = lineStarts(source)
            return EditorDiagnostic(false, "Delimitador '${last.first}' sem fechamento", lineAt(starts, last.second), null)
        }
        return EditorDiagnostic(true, "Estrutura válida")
    }

    private fun buildJsonTree(value: Any): String {
        val out = StringBuilder()
        var nodes = 0
        fun visit(v: Any?, label: String, depth: Int) {
            if (nodes++ > 5_000 || depth > 32) return
            out.append("  ".repeat(depth)).append(label)
            when (v) {
                is JSONObject -> {
                    out.append("  {${v.length()}}\n")
                    val keys = v.keys()
                    while (keys.hasNext() && nodes <= 5_000) {
                        val key = keys.next()
                        visit(v.opt(key), key, depth + 1)
                    }
                }
                is JSONArray -> {
                    out.append("  [${v.length()}]\n")
                    for (i in 0 until v.length()) visit(v.opt(i), "[$i]", depth + 1)
                }
                JSONObject.NULL, null -> out.append(" = null\n")
                else -> out.append(" = ").append(v.toString().take(160)).append('\n')
            }
        }
        visit(value, "$", 0)
        if (nodes > 5_000) out.append("… estrutura truncada por segurança\n")
        return out.toString().trimEnd()
    }

    private fun buildXmlTree(source: String): String {
        val doc = secureDocumentBuilder().parse(InputSource(StringReader(source)))
        val out = StringBuilder()
        var nodes = 0
        fun visit(node: org.w3c.dom.Node, depth: Int) {
            if (nodes++ > 5_000 || depth > 32) return
            if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                out.append("  ".repeat(depth)).append(node.nodeName).append('\n')
                val children = node.childNodes
                for (i in 0 until children.length) visit(children.item(i), depth + 1)
            }
        }
        visit(doc.documentElement, 0)
        if (nodes > 5_000) out.append("… estrutura truncada por segurança\n")
        return out.toString().trimEnd()
    }

    private fun lineStarts(source: String): IntArray {
        val list = ArrayList<Int>()
        list += 0
        source.forEachIndexed { index, c -> if (c == '\n') list += index + 1 }
        return list.toIntArray()
    }

    private fun lineAt(starts: IntArray, offset: Int): Int {
        var low = 0
        var high = starts.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= offset) low = mid + 1 else high = mid - 1
        }
        return high.coerceAtLeast(0) + 1
    }

    private fun countOutsideStrings(line: String, target: Char): Int {
        var count = 0
        var quote: Char? = null
        var escaped = false
        for (c in line) {
            if (quote != null) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == quote) quote = null
            } else if (c == '\'' || c == '"') quote = c else if (c == target) count++
        }
        return count
    }

    private fun jsonLineColumn(message: String): Pair<Int?, Int?> {
        val at = Regex("(?:at character|at)\\s+(\\d+)", RegexOption.IGNORE_CASE).find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return null to at
    }
}
