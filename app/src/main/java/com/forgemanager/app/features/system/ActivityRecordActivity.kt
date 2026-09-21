package com.forgemanager.app.features.system

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.forgemanager.app.ForgeApplication
import com.forgemanager.app.core.ui.ForgeActivity
import com.forgemanager.app.features.settings.UiPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Root-backed Activity record inspired by the system-inspection workflow found
 * in advanced Android file managers. It deliberately refuses to fabricate a
 * foreground Activity when Android has not granted a privileged inspection path.
 */
class ActivityRecordActivity : ForgeActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }
    private lateinit var status: TextView
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        if (graph.root.isAuthorized()) refresh() else showRootRequired()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(UiPreferences.background(this@ActivityRecordActivity))

        val top = LinearLayout(this@ActivityRecordActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(UiPreferences.surface(this@ActivityRecordActivity))
        }
        top.addView(button("←") { finish() })
        status = TextView(this@ActivityRecordActivity).apply {
            text = "Activity Record"
            textSize = 15f
            maxLines = 2
            setTextColor(UiPreferences.textPrimary(this@ActivityRecordActivity))
            setPadding(dp(6), 0, dp(6), 0)
        }
        top.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(button("ROOT") { authorizeRoot() })
        top.addView(button("↻") { refresh() })
        addView(top, LinearLayout.LayoutParams(-1, dp(54)))

        val actions = LinearLayout(this@ActivityRecordActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(UiPreferences.elevatedSurface(this@ActivityRecordActivity))
        }
        actions.addView(action("ATUALIZAR") { refresh() })
        actions.addView(action("COPIAR") { copyOutput() })
        addView(actions, LinearLayout.LayoutParams(-1, dp(48)))

        output = TextView(this@ActivityRecordActivity).apply {
            setTextColor(UiPreferences.textPrimary(this@ActivityRecordActivity))
            setBackgroundColor(UiPreferences.background(this@ActivityRecordActivity))
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11.5f
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(28))
        }
        addView(ScrollView(this@ActivityRecordActivity).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = if (label.length > 2) 9f else 16f
        setTextColor(UiPreferences.textPrimary(this@ActivityRecordActivity))
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        minWidth = dp(44)
        setOnClickListener { action() }
    }

    private fun action(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 10f
        setTextColor(UiPreferences.textPrimary(this@ActivityRecordActivity))
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
    }

    private fun showRootRequired() {
        status.text = "Activity Record • root necessário"
        output.text = buildString {
            append("O Android não permite que um app comum leia a pilha completa de Activities de outros apps.\n\n")
            append("Autorize root neste painel para consultar dumpsys activity sem inventar resultados.\n")
            append("A leitura é somente de diagnóstico; nenhuma Activity é encerrada ou alterada.")
        }
    }

    private fun authorizeRoot() {
        if (graph.root.isAuthorized()) {
            refresh()
            return
        }
        status.text = "Solicitando root…"
        scope.launch {
            val ok = runCatching { withContext(Dispatchers.IO) { graph.root.authorize() } }.getOrDefault(false)
            if (ok) refresh() else {
                status.text = "Activity Record • root negado"
                showRootRequired()
                toast("Root indisponível ou negado")
            }
        }
    }

    private fun refresh() {
        if (!graph.root.isAuthorized()) {
            showRootRequired()
            return
        }
        status.text = "Lendo Activity Manager…"
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                val raw = rootCommand("dumpsys activity activities", 20)
                parse(raw)
            }}
            result.onSuccess { text ->
                status.text = "Activity Record • ${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date())}"
                output.text = text
            }.onFailure {
                status.text = "Activity Record • falha"
                output.text = it.message ?: "Falha ao consultar Activity Manager"
            }
        }
    }

    private fun rootCommand(command: String, timeoutSeconds: Long): String {
        val process = ProcessBuilder("su", "-c", command).start()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val outThread = Thread { process.inputStream.use { it.copyTo(stdout) } }.apply { start() }
        val errThread = Thread { process.errorStream.use { it.copyTo(stderr) } }.apply { start() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("dumpsys excedeu o tempo limite")
        }
        outThread.join(2_000)
        errThread.join(2_000)
        if (process.exitValue() != 0) {
            val message = stderr.toString(StandardCharsets.UTF_8).trim().take(600)
            error(message.ifBlank { "dumpsys activity falhou" })
        }
        return stdout.toString(StandardCharsets.UTF_8)
    }

    private fun parse(raw: String): String {
        val lines = raw.lineSequence().toList()
        val priority = lines.filter { line ->
            val t = line.trimStart()
            t.startsWith("topResumedActivity") ||
                t.startsWith("mResumedActivity") ||
                t.startsWith("mCurrentFocus") ||
                t.startsWith("mFocusedApp") ||
                t.startsWith("mLastPausedActivity")
        }.distinct().take(30)

        val records = lines.filter { line ->
            val t = line.trimStart()
            t.startsWith("Hist #") || t.startsWith("* Hist #") ||
                t.startsWith("ActivityRecord{") || t.startsWith("* Task{")
        }.distinct().take(MAX_RECORD_LINES)

        return buildString {
            append("Forge Manager — Activity Record\n")
            append("Fonte: dumpsys activity activities (root)\n\n")
            append("=== ATUAL / FOCO ===\n")
            if (priority.isEmpty()) append("Nenhuma linha de foco encontrada nesta versão do Android.\n")
            else priority.forEach { append(it.trim()).append('\n') }
            append("\n=== HISTÓRICO / PILHA ===\n")
            if (records.isEmpty()) {
                append("O formato do dumpsys deste Android não expôs registros reconhecidos.\n\n")
                append(raw.lineSequence().take(160).joinToString("\n"))
            } else records.forEach { append(it.trim()).append('\n') }
        }.take(MAX_OUTPUT_CHARS)
    }

    private fun copyOutput() {
        val value = output.text?.toString().orEmpty()
        if (value.isBlank()) return
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Activity Record", value))
        toast("Activity Record copiado")
    }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_RECORD_LINES = 1200
        private const val MAX_OUTPUT_CHARS = 180_000
    }
}
