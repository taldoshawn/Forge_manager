package com.forgemanager.app.features.explorer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.forgemanager.app.MainActivity
import java.io.File

/** Clickable path label that lets the user type a filesystem path and jump there. */
class PathJumpTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : TextView(context, attrs, defStyleAttr) {

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener { showPathEditor() }
        setOnLongClickListener {
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Caminho", text))
            Toast.makeText(context, "Caminho copiado", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun showPathEditor() {
        val activity = context as? Activity ?: return
        val input = EditText(context).apply {
            setText(text@this@PathJumpTextView.text)
            setSelection(length())
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            typeface = Typeface.MONOSPACE
            hint = "/storage/emulated/0/Download"
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("Ir para diretório")
            .setMessage("Digite um caminho local. Toque e segure o caminho na barra para copiá-lo.")
            .setView(input)
            .setPositiveButton("Ir", null)
            .setNegativeButton("Cancelar", null)
            .create()

        fun jump() {
            val raw = input.text.toString().trim()
            val normalized = when {
                raw.equals("/sdcard", true) -> "/storage/emulated/0"
                raw.startsWith("/sdcard/") -> "/storage/emulated/0/" + raw.removePrefix("/sdcard/")
                else -> raw
            }
            val target = File(normalized)
            if (!target.exists() || !target.isDirectory) {
                input.error = "Diretório não encontrado"
                return
            }
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_PATH, target.absolutePath)
            context.startActivity(intent)
            activity.finish()
            dialog.dismiss()
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { jump() }
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    jump()
                    true
                } else false
            }
        }
        dialog.show()
    }
}
