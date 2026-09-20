package com.forgemanager.app.features.terminal

import com.forgemanager.app.core.ui.ForgeActivity

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.forgemanager.app.ForgeApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TerminalActivity : ForgeActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }
    private lateinit var terminal: PtyTerminalView
    private lateinit var keyboard: EditText
    private lateinit var modeLabel: TextView
    private var session: PtySession? = null
    private var rootMode = false
    private var cwd: File = File("/storage/emulated/0")
    private var mutatingKeyboard = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(EXTRA_WORKING_DIRECTORY)?.let { File(it).takeIf(File::isDirectory)?.let { dir -> cwd = dir } }
        setContentView(buildUi())
        terminal.post { startSession(false) }
    }

    override fun onDestroy() {
        session?.close()
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)
        val top = LinearLayout(this@TerminalActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        top.addView(button("←") { finish() })
        modeLabel = TextView(this@TerminalActivity).apply {
            text = "Forge PTY • APP"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(8), 0, dp(8), 0)
        }
        top.addView(modeLabel, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(button("ROOT") { toggleRoot() })
        top.addView(button("CLR") { terminal.clearTerminal() })
        addView(top, LinearLayout.LayoutParams(-1, dp(52)))

        terminal = PtyTerminalView(this@TerminalActivity).apply {
            onTerminalResize = { rows, cols -> session?.resize(rows, cols) }
        }
        addView(terminal, LinearLayout.LayoutParams(-1, 0, 1f))

        val extra = LinearLayout(this@TerminalActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(4, 4, 4))
        }
        fun key(label: String, value: String) = Button(this@TerminalActivity).apply {
            text = label; textSize = 10f; setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { session?.send(value); focusKeyboard() }
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
        }
        extra.addView(key("ESC", "\u001b"))
        extra.addView(key("TAB", "\t"))
        extra.addView(key("CTRL-C", "\u0003"))
        extra.addView(key("CTRL-D", "\u0004"))
        extra.addView(key("↑", "\u001b[A"))
        extra.addView(key("↓", "\u001b[B"))
        extra.addView(key("←", "\u001b[D"))
        extra.addView(key("→", "\u001b[C"))
        addView(extra, LinearLayout.LayoutParams(-1, dp(44)))

        keyboard = EditText(this@TerminalActivity).apply {
            setSingleLine(true)
            hint = "Teclado PTY — toque e digite"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(100, 100, 100))
            setBackgroundColor(Color.rgb(10, 10, 10))
            setPadding(dp(12), 0, dp(12), 0)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (mutatingKeyboard || s.isNullOrEmpty()) return
                    val value = s.toString()
                    session?.send(value)
                    mutatingKeyboard = true
                    setText("")
                    mutatingKeyboard = false
                }
            })
            setOnEditorActionListener { _, _, _ -> session?.send("\r"); true }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_ENTER -> { session?.send("\r"); true }
                    KeyEvent.KEYCODE_DEL -> { session?.send("\u007f"); true }
                    else -> false
                }
            }
        }
        addView(keyboard, LinearLayout.LayoutParams(-1, dp(52)))
    }

    private fun startSession(asRoot: Boolean) {
        session?.close()
        session = null
        terminal.clearTerminal()
        val executable = if (asRoot) findSu() else "/system/bin/sh"
        runCatching {
            PtySession(
                executable = executable,
                cwd = cwd.path,
                rows = terminal.rows(),
                cols = terminal.cols(),
                onOutput = { chunk -> runOnUiThread { terminal.write(chunk) } },
                onExit = { code -> runOnUiThread {
                    terminal.write("\r\n[PTY encerrada: $code]\r\n")
                    modeLabel.text = "Forge PTY • encerrada"
                }}
            )
        }.onSuccess {
            session = it
            rootMode = asRoot
            modeLabel.text = "Forge PTY • ${if (rootMode) "ROOT" else "APP"}"
            focusKeyboard()
        }.onFailure {
            AlertDialog.Builder(this).setTitle("Terminal PTY").setMessage(it.message ?: "Falha ao iniciar PTY").setPositiveButton("OK", null).show()
        }
    }

    private fun toggleRoot() {
        if (rootMode) { startSession(false); return }
        scope.launch {
            val authorized = if (graph.root.isAuthorized()) true else withContext(Dispatchers.IO) {
                runCatching { graph.root.authorize() }.getOrDefault(false)
            }
            if (!authorized) { toast("Root indisponível ou negado"); return@launch }
            AlertDialog.Builder(this@TerminalActivity)
                .setTitle("Iniciar PTY root?")
                .setMessage("A próxima sessão terá privilégios de superusuário. Comandos executados nela podem modificar todo o sistema.")
                .setPositiveButton("Iniciar ROOT") { _, _ -> startSession(true) }
                .setNegativeButton("Cancelar", null).show()
        }
    }

    private fun findSu(): String = listOf("/system/xbin/su", "/system/bin/su", "/sbin/su").firstOrNull { File(it).exists() } ?: "su"
    private fun focusKeyboard() { keyboard.requestFocus() }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); minWidth = dp(44); setOnClickListener { action() }
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object { const val EXTRA_WORKING_DIRECTORY = "working_directory" }
}
