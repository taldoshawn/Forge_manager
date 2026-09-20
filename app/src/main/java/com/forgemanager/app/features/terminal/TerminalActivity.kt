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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.forgemanager.app.ForgeApplication
import com.forgemanager.app.core.shell.ShellEscaper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TerminalActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var modeButton: Button
    private var cwd: File = File("/storage/emulated/0")
    private var rootMode = false
    private var currentProcess: Process? = null
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
    }

    override fun onDestroy() {
        currentProcess?.destroy()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(9, 12, 16))

        val bar = LinearLayout(this@TerminalActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(24, 29, 36))
            setPadding(dp(4), 0, dp(4), 0)
        }
        bar.addView(button("←") { finish() })
        bar.addView(TextView(this@TerminalActivity).apply {
            text = "Terminal"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(6), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        modeButton = button("APP") { toggleMode() }
        bar.addView(modeButton)
        bar.addView(button("■") { stopProcess() })
        bar.addView(button("CLR") { output.text = "" })
        addView(bar, LinearLayout.LayoutParams(-1, dp(52)))

        output = TextView(this@TerminalActivity).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(Color.rgb(205, 214, 224))
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        scroll = ScrollView(this@TerminalActivity).apply {
            isFillViewport = true
            addView(output, ScrollView.LayoutParams(-1, -2))
        }
        addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val commandBar = LinearLayout(this@TerminalActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(17, 22, 28))
            setPadding(dp(6), dp(5), dp(6), dp(5))
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

    private fun printBanner() {
        append("Forge Terminal\n")
        append("Shell Android: /system/bin/sh • diretório: ${cwd.path}\n")
        append("Modo APP executa com as permissões do Forge Manager. ROOT exige autorização explícita.\n\n")
        appendPrompt()
    }

    private fun execute() {
        val command = input.text.toString().trim()
        if (command.isEmpty() || currentProcess != null) return
        input.setText("")
        history += command
        historyIndex = history.size
        append("$command\n")

        if (command == "clear") {
            output.text = ""
            appendPrompt()
            return
        }
        if (command == "pwd") {
            append("${cwd.path}\n")
            appendPrompt()
            return
        }
        if (command == "cd" || command.startsWith("cd ")) {
            changeDirectory(command.removePrefix("cd").trim())
            return
        }

        scope.launch {
            val exit = withContext(Dispatchers.IO) { runCommand(command) }
            if (exit != null) append("\n[exit $exit]\n")
            appendPrompt()
        }
    }

    private fun runCommand(command: String): Int? {
        val shellCommand = "cd ${ShellEscaper.quote(cwd.path)} && $command"
        val process = try {
            val builder = if (rootMode) ProcessBuilder("su", "-c", shellCommand)
            else ProcessBuilder("/system/bin/sh", "-c", shellCommand)
            builder.redirectErrorStream(true)
            builder.environment()["HOME"] = filesDir.path
            builder.environment()["TMPDIR"] = cacheDir.path
            builder.environment()["PATH"] = "/system/bin:/system/xbin:/vendor/bin:/product/bin"
            builder.start()
        } catch (error: Throwable) {
            runOnUiThread { append("Falha ao iniciar shell: ${error.message ?: "erro"}\n") }
            return null
        }
        currentProcess = process
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> runOnUiThread { append("$line\n") } }
            }
            return process.waitFor()
        } finally {
            currentProcess = null
        }
    }

    private fun changeDirectory(argument: String) {
        val target = if (argument.isBlank() || argument == "~") filesDir
        else if (argument.startsWith('/')) File(argument) else File(cwd, argument)
        val canonical = runCatching { target.canonicalFile }.getOrNull()
        if (canonical == null || !canonical.isDirectory) append("cd: diretório não encontrado: $argument\n")
        else cwd = canonical
        appendPrompt()
    }

    private fun toggleMode() {
        if (!rootMode) {
            if (!graph.root.isAuthorized()) {
                AlertDialog.Builder(this)
                    .setTitle("Terminal root")
                    .setMessage("ROOT permite executar comandos como superusuário e pode alterar qualquer parte do sistema. Autorize somente se souber o que está fazendo.")
                    .setPositiveButton("Autorizar") { _, _ -> scope.launch {
                        val ok = withContext(Dispatchers.IO) { runCatching { graph.root.authorize() }.getOrDefault(false) }
                        if (ok) { rootMode = true; updateMode() } else toast("Root indisponível ou negado")
                    }}
                    .setNegativeButton("Cancelar", null)
                    .show()
                return
            }
            rootMode = true
        } else rootMode = false
        updateMode()
    }

    private fun updateMode() {
        modeButton.text = if (rootMode) "ROOT" else "APP"
        append("\n[modo ${modeButton.text}]\n")
        appendPrompt()
    }

    private fun stopProcess() {
        val process = currentProcess ?: return
        process.destroy()
        runCatching { process.destroyForcibly() }
        currentProcess = null
        append("\n[processo interrompido]\n")
        appendPrompt()
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

    private fun appendPrompt() {
        val sigil = if (rootMode) "#" else "\$"
        append("$sigil ${cwd.path} > ")
    }

    private fun append(value: String) {
        output.append(value)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_WORKING_DIRECTORY = "working_directory"
    }
}
