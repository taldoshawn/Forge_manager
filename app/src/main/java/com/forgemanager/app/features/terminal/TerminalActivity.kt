package com.forgemanager.app.features.terminal

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.forgemanager.app.ForgeApplication
import com.forgemanager.app.core.shell.ShellEscaper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/** Persistent Android shell. Uses an external `script` PTY helper when the ROM provides one. */
class TerminalActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var modeButton: Button
    private lateinit var sessionLabel: TextView
    private var cwd: File = File("/storage/emulated/0")
    private var rootMode = false
    private var session: ShellSession? = null
    private var readerJob: Job? = null
    private val history = ArrayList<String>()
    private var historyIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(EXTRA_WORKING_DIRECTORY)?.let { requested ->
            val candidate = File(requested)
            if (candidate.isDirectory) cwd = candidate
        }
        setContentView(buildUi())
        printBanner()
        startSession()
    }

    override fun onDestroy() {
        closeSession()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)

        val bar = LinearLayout(this@TerminalActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(5, 8, 12))
            setPadding(dp(4), 0, dp(4), 0)
        }
        bar.addView(button("←") { finish() })
        sessionLabel = TextView(this@TerminalActivity).apply {
            text = "Terminal"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(6), 0, 0, 0)
        }
        bar.addView(sessionLabel, LinearLayout.LayoutParams(0, -2, 1f))
        modeButton = button("APP") { toggleMode() }
        bar.addView(modeButton)
        bar.addView(button("↻") { restartSession() })
        bar.addView(button("CLR") { output.text = "" })
        addView(bar, LinearLayout.LayoutParams(-1, dp(52)))

        output = TextView(this@TerminalActivity).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12.5f
            setTextColor(Color.rgb(212, 223, 235))
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        scroll = ScrollView(this@TerminalActivity).apply {
            isFillViewport = true
            setBackgroundColor(Color.BLACK)
            addView(output, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val keys = LinearLayout(this@TerminalActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(4, 7, 11))
        }
        listOf(
            "ESC" to "\u001B", "TAB" to "\t", "CTRL-C" to "\u0003", "CTRL-D" to "\u0004",
            "CTRL-L" to "\u000C", "↑" to "\u001B[A", "↓" to "\u001B[B", "←" to "\u001B[D", "→" to "\u001B[C"
        ).forEach { (label, data) -> keys.addView(keyButton(label) { sendRaw(data) }) }
        addView(HorizontalScrollView(this@TerminalActivity).apply {
            isHorizontalScrollBarEnabled = false
            addView(keys)
        }, LinearLayout.LayoutParams(-1, dp(46)))

        val commandBar = LinearLayout(this@TerminalActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(8, 12, 18))
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        commandBar.addView(button("↑") { historyUp() })
        commandBar.addView(button("↓") { historyDown() })
        input = EditText(this@TerminalActivity).apply {
            setSingleLine(true)
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(100, 116, 139))
            hint = "comando"
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) { execute(); true } else false
            }
        }
        commandBar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        commandBar.addView(button("RUN") { execute() })
        addView(commandBar, LinearLayout.LayoutParams(-1, dp(58)))
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        minWidth = dp(44)
        setOnClickListener { action() }
    }

    private fun keyButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11f
        setTextColor(Color.rgb(180, 210, 235))
        setBackgroundColor(Color.rgb(10, 16, 24))
        minWidth = dp(58)
        setOnClickListener { action() }
    }

    private fun printBanner() {
        append("Forge Terminal 0.5\n")
        append("Shell persistente • TERM=xterm-256color\n")
        append("Quando o ROM oferece o utilitário 'script', o Forge usa uma ponte PTY; caso contrário usa pipes persistentes.\n")
        append("ROOT só é iniciado após autorização explícita.\n\n")
    }

    private fun startSession() {
        closeSession()
        sessionLabel.text = "Iniciando shell…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { createSession(rootMode) } }
            result.onSuccess { created ->
                session = created
                modeButton.text = if (rootMode) "ROOT" else "APP"
                sessionLabel.text = "${if (rootMode) "ROOT" else "APP"} • ${if (created.pty) "PTY" else "PIPE"}"
                append("\n[session ${if (rootMode) "root" else "app"} • ${if (created.pty) "pty" else "pipe"}]\n")
                readerJob = scope.launch(Dispatchers.IO) { pump(created) }
                sendLine("export TERM=xterm-256color HOME=${ShellEscaper.quote(filesDir.path)} TMPDIR=${ShellEscaper.quote(cacheDir.path)}")
                sendLine("cd ${ShellEscaper.quote(cwd.path)}")
            }.onFailure {
                sessionLabel.text = "Terminal indisponível"
                append("Falha ao iniciar shell: ${it.message ?: "erro"}\n")
            }
        }
    }

    private fun createSession(root: Boolean): ShellSession {
        val ptyHelper = findPtyHelper()
        val builder: ProcessBuilder
        var pty = false
        if (ptyHelper != null) {
            val command = "exec ${ShellEscaper.quote(ptyHelper)} -q -c /system/bin/sh /dev/null"
            builder = if (root) ProcessBuilder("su", "-c", command) else ProcessBuilder("/system/bin/sh", "-c", command)
            pty = true
        } else {
            builder = if (root) ProcessBuilder("su") else ProcessBuilder("/system/bin/sh", "-i")
        }
        builder.redirectErrorStream(true)
        builder.environment()["TERM"] = "xterm-256color"
        builder.environment()["HOME"] = filesDir.path
        builder.environment()["TMPDIR"] = cacheDir.path
        builder.environment()["PATH"] = "/system/bin:/system/xbin:/vendor/bin:/product/bin"
        val process = builder.start()
        val writer = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
        return ShellSession(process, writer, pty)
    }

    private fun findPtyHelper(): String? {
        val candidates = listOf("/system/bin/script", "/system/xbin/script", "/vendor/bin/script")
        return candidates.firstOrNull { File(it).canExecute() }
    }

    private fun pump(active: ShellSession) {
        try {
            active.process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    val chunk = stripAnsi(String(buffer, 0, count))
                    if (chunk.isNotEmpty()) runOnUiThread { append(chunk) }
                }
            }
        } catch (_: Throwable) {
        } finally {
            val exit = runCatching { active.process.waitFor() }.getOrNull()
            runOnUiThread {
                if (session === active) {
                    session = null
                    sessionLabel.text = "Sessão encerrada"
                    append("\n[terminal encerrado${exit?.let { " • exit $it" } ?: ""}]\n")
                }
            }
        }
    }

    private fun execute() {
        val command = input.text.toString()
        if (command.isBlank()) return
        input.setText("")
        history += command
        historyIndex = history.size
        sendLine(command)
    }

    private fun sendLine(value: String) = sendRaw("$value\n")

    private fun sendRaw(value: String) {
        val active = session ?: run { toast("A sessão não está ativa"); return }
        scope.launch(Dispatchers.IO) {
            runCatching {
                active.writer.write(value)
                active.writer.flush()
            }.onFailure { runOnUiThread { append("\n[falha ao enviar entrada: ${it.message}]\n") } }
        }
    }

    private fun toggleMode() {
        if (!rootMode && !graph.root.isAuthorized()) {
            AlertDialog.Builder(this)
                .setTitle("Terminal root")
                .setMessage("ROOT executa uma sessão shell persistente como superusuário. Comandos digitados por você podem alterar qualquer parte do sistema.")
                .setPositiveButton("Autorizar root") { _, _ -> scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { graph.root.authorize() }.getOrDefault(false) }
                    if (ok) { rootMode = true; startSession() } else toast("Root indisponível ou negado")
                }}
                .setNegativeButton("Cancelar", null).show()
            return
        }
        rootMode = !rootMode
        startSession()
    }

    private fun restartSession() {
        append("\n[reiniciando sessão]\n")
        startSession()
    }

    private fun closeSession() {
        readerJob?.cancel()
        readerJob = null
        session?.let { active ->
            runCatching { active.writer.write("exit\n"); active.writer.flush() }
            runCatching { active.writer.close() }
            runCatching { active.process.destroy() }
            runCatching { active.process.destroyForcibly() }
        }
        session = null
    }

    private fun historyUp() {
        if (history.isEmpty()) return
        historyIndex = (historyIndex - 1).coerceAtLeast(0)
        input.setText(history[historyIndex]); input.setSelection(input.length())
    }

    private fun historyDown() {
        if (history.isEmpty()) return
        historyIndex = (historyIndex + 1).coerceAtMost(history.size)
        input.setText(if (historyIndex == history.size) "" else history[historyIndex]); input.setSelection(input.length())
    }

    private fun stripAnsi(value: String): String = ANSI_REGEX.replace(value, "")

    private fun append(value: String) {
        if (!::output.isInitialized) return
        output.append(value)
        if (output.length() > MAX_TERMINAL_CHARS) {
            output.text = output.text.substring(output.length() - TRIM_TO_CHARS)
        }
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private data class ShellSession(val process: Process, val writer: BufferedWriter, val pty: Boolean)

    companion object {
        const val EXTRA_WORKING_DIRECTORY = "working_directory"
        private const val MAX_TERMINAL_CHARS = 1_000_000
        private const val TRIM_TO_CHARS = 700_000
        private val ANSI_REGEX = Regex("\\u001B\\[[0-?]*[ -/]*[@-~]")
    }
}
