package com.forgemanager.app.features.compare

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset

class TextCompareActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var title: TextView
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val left = File(intent.getStringExtra(EXTRA_LEFT) ?: run { finish(); return })
        val right = File(intent.getStringExtra(EXTRA_RIGHT) ?: run { finish(); return })
        if (!left.isFile || !right.isFile) {
            AlertDialog.Builder(this).setTitle("Comparador").setMessage("Selecione dois arquivos locais válidos.")
                .setPositiveButton("OK") { _, _ -> finish() }.show()
            return
        }
        setContentView(buildUi())
        compare(left, right)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(18, 18, 18))
        title = TextView(this@TextCompareActivity).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(35, 35, 35))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 2
        }
        addView(title, LinearLayout.LayoutParams(-1, dp(64)))
        output = TextView(this@TextCompareActivity).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(12f)
            setTextColor(Color.rgb(220, 220, 220))
            setPadding(dp(10), dp(10), dp(10), dp(24))
            setTextIsSelectable(true)
        }
        addView(ScrollView(this@TextCompareActivity).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun compare(left: File, right: File) {
        title.text = "${left.name}  ↔  ${right.name}"
        output.text = "Comparando…"
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val leftText = readTextBounded(left)
                    val rightText = readTextBounded(right)
                    LineDiff.compare(leftText.lines, rightText.lines) to (leftText.truncated || rightText.truncated)
                }
            }
            result.onSuccess { (diff, truncated) -> render(diff, truncated) }
                .onFailure { error ->
                    AlertDialog.Builder(this@TextCompareActivity).setTitle("Comparador")
                        .setMessage(error.message ?: "Falha ao comparar")
                        .setPositiveButton("OK", null).show()
                    output.text = ""
                }
        }
    }

    private fun render(diff: List<DiffLine>, truncated: Boolean) {
        val builder = SpannableStringBuilder()
        if (truncated) {
            builder.append("Prévia limitada por segurança/desempenho.\n\n")
        }
        diff.forEach { line ->
            val prefix = when (line.kind) {
                DiffKind.SAME -> "  "
                DiffKind.ADDED -> "+ "
                DiffKind.REMOVED -> "- "
            }
            val leftNo = line.leftNumber?.toString()?.padStart(5) ?: "     "
            val rightNo = line.rightNumber?.toString()?.padStart(5) ?: "     "
            val start = builder.length
            builder.append(prefix).append(leftNo).append(" ").append(rightNo).append("  ").append(line.text).append('\n')
            val end = builder.length
            when (line.kind) {
                DiffKind.ADDED -> {
                    builder.setSpan(BackgroundColorSpan(Color.rgb(22, 66, 38)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(ForegroundColorSpan(Color.rgb(202, 255, 214)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                DiffKind.REMOVED -> {
                    builder.setSpan(BackgroundColorSpan(Color.rgb(78, 31, 31)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(ForegroundColorSpan(Color.rgb(255, 214, 214)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                DiffKind.SAME -> Unit
            }
        }
        output.text = builder
    }

    private fun readTextBounded(file: File): TextSnapshot {
        require(file.length() <= ABSOLUTE_MAX_BYTES) { "Arquivo grande demais para comparação de texto" }
        val max = minOf(file.length(), PREVIEW_MAX_BYTES).toInt()
        val bytes = file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream(max.coerceAtMost(256 * 1024))
            val buffer = ByteArray(64 * 1024)
            var remaining = max
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (n < 0) break
                out.write(buffer, 0, n)
                remaining -= n
            }
            out.toByteArray()
        }
        val (charset, skip) = detectCharset(bytes)
        val text = bytes.copyOfRange(skip, bytes.size).toString(charset)
        val lines = text.lineSequence().take(MAX_LINES).toList()
        return TextSnapshot(lines, file.length() > PREVIEW_MAX_BYTES || text.lineSequence().drop(MAX_LINES).iterator().hasNext())
    }

    private fun detectCharset(data: ByteArray): Pair<Charset, Int> = when {
        data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3
        data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
        data.size >= 2 && data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
        else -> Charsets.UTF_8 to 0
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private data class TextSnapshot(val lines: List<String>, val truncated: Boolean)

    companion object {
        const val EXTRA_LEFT = "left_path"
        const val EXTRA_RIGHT = "right_path"
        private const val PREVIEW_MAX_BYTES = 4L * 1024 * 1024
        private const val ABSOLUTE_MAX_BYTES = 64L * 1024 * 1024
        private const val MAX_LINES = 8_000
    }
}
