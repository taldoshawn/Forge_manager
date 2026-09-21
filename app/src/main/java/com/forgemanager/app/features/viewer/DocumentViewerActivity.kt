package com.forgemanager.app.features.viewer

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import com.forgemanager.app.ForgeApplication
import com.forgemanager.app.core.file.FileLocation
import com.forgemanager.app.core.file.fileDisplayName
import com.forgemanager.app.core.file.readFileLocation
import com.forgemanager.app.core.ui.ForgeActivity
import com.forgemanager.app.features.editor.LineNumberEditText
import com.forgemanager.app.features.settings.UiPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URLConnection
import java.util.Locale
import java.util.zip.ZipFile

class DocumentViewerActivity : ForgeActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }
    private var location: FileLocation? = null
    private var incomingUri: Uri? = null
    private lateinit var displayName: String
    private lateinit var status: TextView
    private lateinit var content: FrameLayout
    private var localFile: File? = null
    private var ownedLocalFile = false
    private var pdfDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var pdfPageIndex = 0
    private var pdfBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        location = intent.readFileLocation()
        incomingUri = intent.data
        if (location == null && incomingUri == null) { finish(); return }
        displayName = intent.fileDisplayName()
            ?: incomingUri?.let(::queryDisplayName)
            ?: location?.displayPath?.substringAfterLast('/')?.substringAfterLast("!/")
            ?: "Documento"
        setContentView(buildUi())
        load()
    }

    override fun onDestroy() {
        closePdf()
        if (ownedLocalFile) localFile?.delete()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(UiPreferences.background(this@DocumentViewerActivity))
        val bar = LinearLayout(this@DocumentViewerActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(UiPreferences.surface(this@DocumentViewerActivity))
        }
        bar.addView(tool("←", "Voltar") { finish() })
        status = TextView(this@DocumentViewerActivity).apply {
            text = displayName
            maxLines = 2
            textSize = 12.5f
            setTextColor(UiPreferences.textPrimary(this@DocumentViewerActivity))
            setPadding(dp(6), 0, dp(6), 0)
        }
        bar.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(tool("↗", "Abrir externamente") { openExternally() })
        bar.addView(tool("⋮", "Informações") { showInfo() })
        addView(bar, LinearLayout.LayoutParams(-1, dp(54)))
        content = FrameLayout(this@DocumentViewerActivity).apply {
            setBackgroundColor(UiPreferences.background(this@DocumentViewerActivity))
        }
        addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun tool(label: String, description: String, action: () -> Unit) = Button(this).apply {
        text = label
        contentDescription = description
        minWidth = dp(44)
        minimumWidth = dp(44)
        setTextColor(UiPreferences.textPrimary(this@DocumentViewerActivity))
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { action() }
    }

    private fun load() {
        status.text = "Abrindo $displayName…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { materialize() } }
                .onSuccess { file ->
                    localFile = file
                    when (file.extension.lowercase(Locale.ROOT)) {
                        "pdf" -> openPdf(file)
                        "docx", "xlsx", "pptx", "odt", "ods", "odp" -> loadZippedDocument(file)
                        "rtf" -> loadRtf(file)
                        "doc", "xls", "ppt" -> renderLegacyNotice(file.extension.uppercase(Locale.ROOT))
                        else -> renderLegacyNotice(file.extension.uppercase(Locale.ROOT).ifBlank { "DOCUMENTO" })
                    }
                }.onFailure { showError(it.message ?: "Falha ao abrir documento") }
        }
    }

    private suspend fun materialize(): File {
        val direct = (location as? FileLocation.Direct)?.path?.let(::File)?.takeIf { it.isFile && it.canRead() }
        if (direct != null) return direct
        val dir = File(cacheDir, "shared").apply { mkdirs() }
        val safe = displayName.replace(Regex("[^A-Za-z0-9._() -]"), "_").take(150).ifBlank { "document.bin" }
        val target = File(dir, "doc-${System.nanoTime()}-$safe")
        val input = location?.let { graph.resolver.backendFor(it).openInput(it) }
            ?: incomingUri?.let { contentResolver.openInputStream(it) }
            ?: error("Fonte indisponível")
        input.use { source ->
            FileOutputStream(target).buffered(COPY_BUFFER).use { output ->
                val buffer = ByteArray(COPY_BUFFER)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_DOCUMENT_BYTES) { "Documento grande demais" }
                    output.write(buffer, 0, read)
                }
            }
        }
        ownedLocalFile = true
        return target
    }

    private fun openPdf(file: File) {
        runCatching {
            closePdf()
            pdfDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(pdfDescriptor!!)
            require(pdfRenderer!!.pageCount > 0) { "PDF sem páginas" }
            pdfPageIndex = 0
            renderPdfPage()
        }.onFailure { showError(it.message ?: "PDF inválido") }
    }

    private fun renderPdfPage() {
        val renderer = pdfRenderer ?: return
        val page = renderer.openPage(pdfPageIndex.coerceIn(0, renderer.pageCount - 1))
        try {
            val targetWidth = (resources.displayMetrics.widthPixels * 2).coerceIn(1080, 3200)
            val targetHeight = (targetWidth.toLong() * page.height / page.width).toInt().coerceIn(1, 5000)
            pdfBitmap?.recycle()
            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            pdfBitmap = bitmap
            val pageLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(UiPreferences.background(this@DocumentViewerActivity))
            }
            val image = ZoomImageView(this).apply {
                setBackgroundColor(UiPreferences.background(this@DocumentViewerActivity))
                setImageBitmap(bitmap)
            }
            pageLayout.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
            pageLayout.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setBackgroundColor(UiPreferences.surface(this@DocumentViewerActivity))
                addView(tool("◀", "Página anterior") {
                    if (pdfPageIndex > 0) { pdfPageIndex--; renderPdfPage() }
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(TextView(this@DocumentViewerActivity).apply {
                    text = "${pdfPageIndex + 1} / ${renderer.pageCount}"
                    gravity = Gravity.CENTER
                    setTextColor(UiPreferences.textPrimary(this@DocumentViewerActivity))
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(tool("▶", "Próxima página") {
                    if (pdfPageIndex + 1 < renderer.pageCount) { pdfPageIndex++; renderPdfPage() }
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
            })
            content.removeAllViews()
            content.addView(pageLayout, FrameLayout.LayoutParams(-1, -1))
            status.text = "$displayName • PDF • página ${pdfPageIndex + 1}/${renderer.pageCount}"
        } finally {
            page.close()
        }
    }

    private fun loadZippedDocument(file: File) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { extractOfficeText(file) } }
                .onSuccess { renderText(it.first, it.second) }
                .onFailure { showError(it.message ?: "Falha ao ler documento") }
        }
    }

    private fun extractOfficeText(file: File): Pair<String, String> {
        val ext = file.extension.lowercase(Locale.ROOT)
        ZipFile(file).use { zip ->
            val text = when (ext) {
                "docx" -> zip.getEntry("word/document.xml")?.let { xmlToText(zip.getInputStream(it).bufferedReader().use { r -> r.readText() }) }
                "xlsx" -> zip.getEntry("xl/sharedStrings.xml")?.let { xmlToText(zip.getInputStream(it).bufferedReader().use { r -> r.readText() }) }
                "pptx" -> zip.entries().asSequence()
                    .filter { !it.isDirectory && Regex("ppt/slides/slide\\d+\\.xml").matches(it.name) }
                    .sortedBy { it.name }
                    .joinToString("\n\n") { entry ->
                        "--- ${entry.name.substringAfterLast('/')} ---\n" + xmlToText(zip.getInputStream(entry).bufferedReader().use { it.readText() })
                    }
                "odt", "ods", "odp" -> zip.getEntry("content.xml")?.let { xmlToText(zip.getInputStream(it).bufferedReader().use { r -> r.readText() }) }
                else -> null
            } ?: error("Estrutura de documento não reconhecida")
            return text.take(MAX_PREVIEW_CHARS) to ext.uppercase(Locale.ROOT)
        }
    }

    private fun xmlToText(xml: String): String = xml
        .replace(Regex("</(?:w:p|text:p|a:p|table:table-row)>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<(?:w:tab|text:tab)[^>]*/>", RegexOption.IGNORE_CASE), "\t")
        .replace(Regex("<[^>]+>"), "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
        .replace(Regex("[ \t]+\n"), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    private fun loadRtf(file: File) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                require(file.length() <= MAX_RTF_BYTES) { "RTF grande demais" }
                val raw = file.readText(Charsets.ISO_8859_1)
                raw.replace(Regex("\\\\par\\b ?"), "\n")
                    .replace(Regex("\\\\'[0-9a-fA-F]{2}")) { m ->
                        val code = m.value.substring(2).toInt(16)
                        byteArrayOf(code.toByte()).toString(Charsets.ISO_8859_1)
                    }
                    .replace(Regex("\\\\[a-zA-Z]+-?\\d* ?"), "")
                    .replace(Regex("[{}]"), "")
                    .take(MAX_PREVIEW_CHARS)
            }}.onSuccess { renderText(it, "RTF") }.onFailure { showError(it.message ?: "RTF inválido") }
        }
    }

    private fun renderText(text: String, label: String) {
        val editor = LineNumberEditText(this).apply {
            applyPalette(
                UiPreferences.background(this@DocumentViewerActivity), UiPreferences.textPrimary(this@DocumentViewerActivity),
                UiPreferences.textSecondary(this@DocumentViewerActivity), UiPreferences.divider(this@DocumentViewerActivity)
            )
            setWordWrapEnabled(true)
            setText(text)
            keyListener = null
            setTextIsSelectable(true)
            setSelection(0)
        }
        content.removeAllViews()
        content.addView(editor, FrameLayout.LayoutParams(-1, -1))
        status.text = "$displayName • $label • prévia de texto"
    }

    private fun renderLegacyNotice(label: String) {
        renderText(
            "$label é um formato binário legado ou não possui renderizador nativo seguro nesta versão.\n\n" +
                "O arquivo foi aberto pelo Forge Manager e pode ser enviado para um visualizador compatível usando o botão ↗.\n\n" +
                "DOCX/XLSX/PPTX/ODT/ODS/ODP e PDF possuem visualização interna.",
            label
        )
    }

    private fun showInfo() {
        val file = localFile
        AlertDialog.Builder(this).setTitle(displayName)
            .setMessage("Tipo: ${file?.extension?.uppercase(Locale.ROOT).orEmpty()}\nTamanho: ${file?.length() ?: 0} bytes\nFonte: ${location?.displayPath ?: incomingUri}")
            .setPositiveButton("OK", null).show()
    }

    private fun openExternally() {
        val uri = incomingUri ?: localFile?.let { FileProvider.getUriForFile(this, "$packageName.files", it) }
            ?: run { showError("Arquivo indisponível"); return }
        val mime = mimeFor(displayName)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(intent, "Abrir documento com")) }
            .onFailure { showError("Nenhum aplicativo compatível instalado") }
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "odt" -> "application/vnd.oasis.opendocument.text"
        "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
        "odp" -> "application/vnd.oasis.opendocument.presentation"
        "rtf" -> "application/rtf"
        else -> URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun closePdf() {
        pdfBitmap?.recycle(); pdfBitmap = null
        pdfRenderer?.close(); pdfRenderer = null
        pdfDescriptor?.close(); pdfDescriptor = null
    }

    private fun showError(message: String) = runCatching {
        if (!isFinishing && !isDestroyed) AlertDialog.Builder(this).setTitle("Visualizador de documentos").setMessage(message).setPositiveButton("OK", null).show()
    }

    private fun <T> java.util.Enumeration<T>.asSequence(): Sequence<T> = sequence {
        while (hasMoreElements()) yield(nextElement())
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val COPY_BUFFER = 128 * 1024
        private const val MAX_DOCUMENT_BYTES = 512L * 1024 * 1024
        private const val MAX_RTF_BYTES = 32L * 1024 * 1024
        private const val MAX_PREVIEW_CHARS = 4_000_000
    }
}
