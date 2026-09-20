package com.forgemanager.app.features.settings

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun render() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiPreferences.surface(this@SettingsActivity))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.BLACK)
            addView(textButton("‹") { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(TextView(this@SettingsActivity).apply {
                text = "Configurações"
                textSize = 20f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(top, LinearLayout.LayoutParams(-1, dp(64)))
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(28))
        }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        section("Aparência")
        toggle("Preto AMOLED", "Usa preto puro em superfícies e barras.", UiPreferences.amoled(this)) {
            UiPreferences.setAmoled(this, it); render()
        }
        action("Cor de destaque", accentLabel()) { chooseAccent() }
        toggle("Ícones grandes", "Aumenta os ícones de arquivos sem desperdiçar espaço do painel.", UiPreferences.largeIcons(this)) {
            UiPreferences.setLargeIcons(this, it)
        }
        toggle("Linhas compactas", "Mostra mais arquivos por tela.", UiPreferences.compactRows(this)) {
            UiPreferences.setCompactRows(this, it)
        }

        section("Explorador")
        toggle("Mostrar ocultos por padrão", "Novos painéis iniciam exibindo nomes que começam com ponto.", UiPreferences.showHiddenDefault(this)) {
            UiPreferences.setShowHiddenDefault(this, it)
        }
        toggle("Abrir arquivos compactados internamente", "ZIP/JAR/APK/AAB/XAPK entram no navegador de arquivos do Forge.", UiPreferences.openArchivesInternally(this)) {
            UiPreferences.setOpenArchivesInternally(this, it)
        }
        toggle("Confirmar exclusão", "Mantém uma confirmação antes de apagar permanentemente.", UiPreferences.confirmDelete(this)) {
            UiPreferences.setConfirmDelete(this, it)
        }

        section("Ferramentas avançadas")
        info("DEX / Smali", "Inspector de classes, métodos, campos e strings; desmontagem para Smali e rebuild DEX.")
        info("APK / recursos", "Resource Studio, AXML/ARSC, ZIP editing, zipalign e assinatura APK v1/v2/v3/v4.")
        info("Terminal", "Terminal PTY nativo com programas interativos, root opcional e diretório do painel ativo.")
        info("Acesso", "SAF, acesso a todos os arquivos, Shizuku e root continuam independentes e explícitos.")
    }

    private fun section(title: String) {
        content.addView(TextView(this).apply {
            text = title.uppercase()
            textSize = 11f
            setTextColor(UiPreferences.accent(this@SettingsActivity))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(6), dp(18), dp(6), dp(7))
        })
    }

    @Suppress("DEPRECATION")
    private fun toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(title(title))
        texts.addView(subtitle(subtitle))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Switch(this).apply {
            isChecked = checked
            buttonTintList = null
            setOnCheckedChangeListener { _, value -> onChange(value) }
        })
        content.addView(row, cardParams())
    }

    private fun action(title: String, value: String, click: () -> Unit) {
        val row = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(title(title))
        texts.addView(subtitle(value))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = "›"
            textSize = 26f
            setTextColor(UiPreferences.accent(this@SettingsActivity))
        })
        content.addView(row, cardParams())
    }

    private fun info(title: String, subtitle: String) {
        val row = card().apply { orientation = LinearLayout.VERTICAL }
        row.addView(title(title))
        row.addView(subtitle(subtitle))
        content.addView(row, cardParams())
    }

    private fun chooseAccent() {
        val names = arrayOf("Azul", "Ciano", "Roxo", "Verde", "Laranja", "Vermelho")
        val values = arrayOf("blue", "cyan", "purple", "green", "orange", "red")
        val current = values.indexOf(UiPreferences.accentName(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Cor de destaque")
            .setSingleChoiceItems(names, current) { dialog, which ->
                UiPreferences.setAccent(this, values[which])
                dialog.dismiss()
                render()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun accentLabel(): String = when (UiPreferences.accentName(this)) {
        "cyan" -> "Ciano"
        "purple" -> "Roxo"
        "green" -> "Verde"
        "orange" -> "Laranja"
        "red" -> "Vermelho"
        else -> "Azul"
    }

    private fun card() = LinearLayout(this).apply {
        setPadding(dp(14), dp(12), dp(12), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(UiPreferences.elevatedSurface(this@SettingsActivity))
            setStroke(dp(1), Color.rgb(28, 35, 46))
        }
    }

    private fun cardParams() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    private fun title(value: String) = TextView(this).apply { text = value; textSize = 15f; setTextColor(Color.WHITE) }
    private fun subtitle(value: String) = TextView(this).apply { text = value; textSize = 11.5f; setTextColor(Color.rgb(143, 153, 168)); setPadding(0, dp(3), dp(8), 0) }
    private fun textButton(value: String, click: () -> Unit) = TextView(this).apply {
        text = value
        gravity = Gravity.CENTER
        textSize = 31f
        setTextColor(Color.WHITE)
        setOnClickListener { click() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
