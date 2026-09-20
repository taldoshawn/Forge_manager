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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TerminalActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }
    private val bridge by lazy { PtyBridge() }
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var modeButton: Button
    private var workingDirectory = File("/storage/emulated/0")
    private var rootMode = false
    private var session: PtyBridge.PtySession? = null
    private var readerJob: Job? = null
    private val history = ArrayList<String>()
    private var historyIndex = 0
    private val terminalBuffer = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(EXTRA_WORKING_DIRECTORY)?.let { requested ->
            val candidate = File(requested)
            if (candidate.isDirectory) workingDirectory = candidate
        }
        setContentView(buildUi())
        appendPlain("Forge Terminal PTY\nSessão persistente • TERM=xterm-256color\n\n")
        startSession()
    }

    override fun onDestroy() {
        readerJob?.cancel()
        session?.close()
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)

        val bar = LinearLayout(this@TerminalActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(8, 8, 8))
            setPadding(dp(4), 0, dp(4), 0)
        }
        bar.addView(button("←") { finish() })
        bar.addView(TextView(this@TerminalActivity).apply {
            text = "Terminal PTY"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(6), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        modeButton = button("APP") { toggleMode() }
        bar.addView(modeButton)
        bar.addView(button("↻") { restartSession() })
        bar.addView(button("CLR") { clearScreen() })
        addView(bar, LinearLayout.LayoutParams(-1, dp(50)))

        val keys = HorizontalScrollView(this@TerminalActivity).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(this@TerminalActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(key("ESC") { sendBytes(byteArrayOf(0x1b)) })
                addView(key("TAB") { sendBytes(byteArrayOf(0x09)) })
                addView(key("CTRL-C") { sendBytes(byteArrayOf(0x03)) })
                addView(key("CTRL-D") { sendBytes(byteArrayOf(0x04)) })
                addView(key("CTRL-Z") { sendBytes(byteArrayOf(0x1a)) })
                addView(key("↑") { send("\u001b[A") })
                addView(key("↓") { send("\u001b[B") })
                addView(key("←") { send("\u001b[D") })
                addView(key("→") { send("\u001b[C") })
                addView(key("HOME") { send("\u001b[H") })
                addView(key("END") { send("\u001b[F") })
            })
        }
        addView(keys, LinearLayout.LayoutParams(-1, dp(46)))

        output = TextView(this@TerminalActivity).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12.5f
            setTextColor(Color.rgb(220, 228, 235))
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.BLACK)
        }
        scroll = ScrollView(this@TerminalActivity).apply {
            isFillViewport = true
            setBackgroundColor(Color.BLACK)
            addView(HorizontalScrollView(this@TerminalActivity).apply {
                isFillViewport = true
                addView(output, android.widget.FrameLayout.LayoutParams(-2, -2))
            }, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        output.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> resizePty() }

        val commandBar = LinearLayout(this@TerminalActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(8, 8, 8))
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        commandBar.addView(button("↑") { historyUp() })
        commandBar.addView(button("↓") { historyDown() })
        input = EditText(this@TerminalActivity).apply {
            setSingleLine(true)
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(110, 110, 110))
            setBackgroundColor(Color.TRANSPARENT)
            hint = "comando ou entrada para o processo"
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) { submitInput(); true } else false
            }
        }
        commandBar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        commandBar.addView(button("SEND") { submitInput() })
        addView(commandBar, LinearLayout.LayoutParams(-1, dp(56)))
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        minWidth = dp(44)
        minHeight = dp(44)
        setOnClickListener { action() }
    }

    private fun key(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11f
        setTextColor(Color.rgb(0, 200, 255))
        setBackgroundColor(Color.TRANSPARENT)
        minHeight = dp(42)
        setOnClickListener { action() }
    }

    private fun startSession() {
        readerJob?.cancel()
        session?.close()
        session = null
        val argv = if (rootMode) arrayOf("su", "-c", "exec /system/bin/sh -l")
        else arrayOf("/system/bin/sh", "-l")
        val env = mapOf(
            "HOME" to filesDir.path,
            "TMPDIR" to cacheDir.path,
            "PATH" to "/system/bin:/system/xbin:/vendor/bin:/product/bin:${filesDir.path}/bin",
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor"
        )
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { bridge.spawn(argv, workingDirectory.path, env, 24, 80) }
            }
            result.onSuccess { newSession ->
                session = newSession
                appendPlain("[PTY ${if (rootMode) "ROOT" else "APP"} iniciado, pid=${newSession.pid}]\n")
                resizePty()
                readerJob = scope.launch(Dispatchers.IO) { readLoop(newSession) }
            }.onFailure {
                appendPlain("[falha ao iniciar PTY: ${it.message ?: it.javaClass.simpleName}]\n")
            }
        }
    }

    private fun restartSession() {
        appendPlain("\n[reiniciando sessão]\n")
        startSession()
    }

    private fun readLoop(current: PtyBridge.PtySession) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (session === current) {
                val count = current.read(buffer)
                if (count <= 0) break
                val chunk = buffer.copyOf(count).toString(Charsets.UTF_8)
                runOnUiThread { appendTerminal(chunk) }
            }
        } catch (error: Throwable) {
            if (session === current) runOnUiThread { appendPlain("\n[PTY: ${error.message ?: "encerrado"}]\n") }
        } finally {
            val code = runCatching { current.waitFor(false) }.getOrDefault(-1)
            if (session === current) runOnUiThread { appendPlain("\n[sessão encerrada${if (code >= 0) ", exit $code" else ""}]\n") }
        }
    }

    private fun submitInput() {
        val value = input.text.toString()
        if (value.isEmpty()) { send("\r"); return }
        history += value
        while (history.size > 200) history.removeAt(0)
        historyIndex = history.size
        input.setText("")
        send(value + "\r")
    }

    private fun send(value: String) = sendBytes(value.toByteArray(Charsets.UTF_8))

    private fun sendBytes(bytes: ByteArray) {
        val current = session ?: run { toast("PTY não está ativo"); return }
        scope.launch(Dispatchers.IO) {
            runCatching { current.write(bytes) }.onFailure { runOnUiThread { toast("Falha ao enviar para PTY") } }
        }
    }

    private fun toggleMode() {
        if (!rootMode && !graph.root.isAuthorized()) {
            AlertDialog.Builder(this)
                .setTitle("Terminal root")
                .setMessage("ROOT executa uma shell interativa como superusuário. Ela pode alterar ou apagar dados do sistema. Autorize somente quando realmente precisar.")
                .setPositiveButton("Autorizar") { _, _ ->
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { runCatching { graph.root.authorize() }.getOrDefault(false) }
                        if (ok) { rootMode = true; modeButton.text = "ROOT"; restartSession() }
                        else toast("Root indisponível ou negado")
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        rootMode = !rootMode
        modeButton.text = if (rootMode) "ROOT" else "APP"
        restartSession()
    }

    private fun resizePty() {
        if (!::output.isInitialized) return
        val width = output.width.coerceAtLeast(dp(320))
        val height = scroll.height.coerceAtLeast(dp(200))
        val charWidth = output.paint.measureText("M").coerceAtLeast(1f)
        val lineHeight = output.lineHeight.coerceAtLeast(1)
        val cols = (width / charWidth).toInt().coerceIn(20, 240)
        val rows = (height / lineHeight).coerceIn(5, 100)
        session?.resize(rows, cols)
    }

    private fun historyUp() {
        if (history.isEmpty()) return
        historyIndex = (historyIndex - 1).coerceAtLeast(0)
        input.setText(history[historyIndex])
        input.setSelection(input.length())
    }

    private fun historyDown() {
        if (history.isEmpty()) return
        historyIndex = (historyIndex + 1).coerceAtMost(history.size)
        val value = if (historyIndex == history.size) "" else history[historyIndex]
        input.setText(value)
        input.setSelection(input.length())
    }

    private fun appendTerminal(raw: String) {
        val clean = ANSI_OSC.replace(ANSI_CSI.replace(raw, ""), "")
        clean.forEach { ch ->
            when (ch) {
                '\b' -> if (terminalBuffer.isNotEmpty() && terminalBuffer.last() != '\n') terminalBuffer.deleteCharAt(terminalBuffer.lastIndex)
                '\r' -> if (terminalBuffer.isNotEmpty() && terminalBuffer.last() != '\n') terminalBuffer.append('\n')
                else -> terminalBuffer.append(ch)
            }
        }
        trimTerminalBuffer()
        output.text = terminalBuffer.toString()
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun appendPlain(value: String) {
        terminalBuffer.append(value)
        trimTerminalBuffer()
        if (::output.isInitialized) {
            output.text = terminalBuffer.toString()
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun clearScreen() {
        terminalBuffer.clear()
        output.text = ""
    }

    private fun trimTerminalBuffer() {
        val extra = terminalBuffer.length - MAX_BUFFER_CHARS
        if (extra > 0) terminalBuffer.delete(0, extra)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_WORKING_DIRECTORY = "working_directory"
        private const val MAX_BUFFER_CHARS = 500_000
        private val ANSI_CSI = Regex("\\u001B\\[[0-?]*[ -/]*[@-~]")
        private val ANSI_OSC = Regex("\\u001B\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)")
    }
}
