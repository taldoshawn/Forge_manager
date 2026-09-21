package com.forgemanager.app.features.settings

import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.forgemanager.app.core.ui.ForgeActivity

class SettingsActivity : ForgeActivity() {
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun render() {
        refreshSystemBars()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiPreferences.background(this@SettingsActivity))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(12), dp(4))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(UiPreferences.accent(this@SettingsActivity), UiPreferences.accentAlt(this@SettingsActivity))
            )
            addView(textButton("‹") { finish() }, LinearLayout.LayoutParams(dp(48), dp(52)))
            addView(TextView(this@SettingsActivity).apply {
                text = "Configurações"
                textSize = 19f
                setTextColor(android.graphics.Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(top, LinearLayout.LayoutParams(-1, dp(58)))
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(28))
        }
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        section("Aparência")
        action("Tema do fundo", themeLabel()) { chooseTheme() }
        action("Cores do app", accentLabel()) { chooseAccent() }
        toggle("Ícones grandes", "Aumenta os ícones de arquivos sem ampliar demais as linhas.", UiPreferences.largeIcons(this)) {
            UiPreferences.setLargeIcons(this, it)
        }
        toggle("Linhas compactas", "Mostra mais arquivos em cada painel.", UiPreferences.compactRows(this)) {
            UiPreferences.setCompactRows(this, it)
        }

        section("Explorador")
        toggle("Mostrar ocultos por padrão", "Novos painéis exibem nomes iniciados por ponto.", UiPreferences.showHiddenDefault(this)) {
            UiPreferences.setShowHiddenDefault(this, it)
        }
        toggle("Abrir compactados internamente", "ZIP/JAR/APK/AAB/XAPK usam o navegador de arquivos do Forge quando suportado.", UiPreferences.openArchivesInternally(this)) {
            UiPreferences.setOpenArchivesInternally(this, it)
        }
        toggle("Confirmar exclusão", "Pede confirmação antes da exclusão permanente.", UiPreferences.confirmDelete(this)) {
            UiPreferences.setConfirmDelete(this, it)
        }

        section("Ferramentas")
        info("DEX / Smali", "Inspector, navegação, busca, cache e rebuild integrados ao gerenciador.")
        info("APK / recursos", "Resource Studio, AXML/ARSC, zipalign e assinatura continuam integrados.")
        info("Acesso", "SAF, Shizuku e root continuam opt-in e independentes.")
    }

    private fun section(title: String) {
        content.addView(TextView(this).apply {
            text = title.uppercase()
            textSize = 10.5f
            setTextColor(UiPreferences.accent(this@SettingsActivity))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), dp(18), dp(4), dp(7))
        })
    }

    @Suppress("DEPRECATION")
    private fun toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = row().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(title(title))
        texts.addView(subtitle(subtitle))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Switch(this).apply {
            isChecked = checked
            buttonTintList = null
            setOnCheckedChangeListener { _, value -> onChange(value) }
        })
        content.addView(row, rowParams())
    }

    private fun action(title: String, value: String, click: () -> Unit) {
        val row = row().apply {
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
            textSize = 24f
            setTextColor(UiPreferences.accent(this@SettingsActivity))
        })
        content.addView(row, rowParams())
    }

    private fun info(title: String, subtitle: String) {
        content.addView(row().apply {
            orientation = LinearLayout.VERTICAL
            addView(title(title))
            addView(subtitle(subtitle))
        }, rowParams())
    }

    private fun chooseTheme() {
        val names = arrayOf("Branco", "Cinza", "Preto normal")
        val values = arrayOf(UiPreferences.THEME_WHITE, UiPreferences.THEME_GRAY, UiPreferences.THEME_DARK)
        val current = values.indexOf(UiPreferences.themeMode(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Tema do fundo")
            .setSingleChoiceItems(names, current) { dialog, which ->
                UiPreferences.setThemeMode(this, values[which])
                dialog.dismiss()
                render()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun chooseAccent() {
        val names = arrayOf("Azul + ciano", "Ciano + azul", "Roxo + rosa", "Verde + turquesa", "Laranja + vermelho", "Vermelho + roxo", "Rosa + violeta")
        val values = arrayOf("azure", "cyan", "violet", "green", "orange", "red", "pink")
        val current = values.indexOf(UiPreferences.accentName(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Cores do app")
            .setSingleChoiceItems(names, current) { dialog, which ->
                UiPreferences.setAccent(this, values[which])
                dialog.dismiss()
                render()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun themeLabel(): String = when (UiPreferences.themeMode(this)) {
        UiPreferences.THEME_GRAY -> "Cinza"
        UiPreferences.THEME_DARK -> "Preto normal"
        else -> "Branco"
    }

    private fun accentLabel(): String = when (UiPreferences.accentName(this)) {
        "cyan" -> "Ciano + azul"
        "violet" -> "Roxo + rosa"
        "green" -> "Verde + turquesa"
        "orange" -> "Laranja + vermelho"
        "red" -> "Vermelho + roxo"
        "pink" -> "Rosa + violeta"
        else -> "Azul + ciano"
    }

    private fun row() = LinearLayout(this).apply {
        setPadding(dp(14), dp(11), dp(12), dp(11))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(UiPreferences.elevatedSurface(this@SettingsActivity))
            setStroke(dp(1), UiPreferences.divider(this@SettingsActivity))
        }
    }

    private fun rowParams() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) }
    private fun title(value: String) = TextView(this).apply {
        text = value
        textSize = 14.5f
        setTextColor(UiPreferences.textPrimary(this@SettingsActivity))
    }
    private fun subtitle(value: String) = TextView(this).apply {
        text = value
        textSize = 11.3f
        setTextColor(UiPreferences.textSecondary(this@SettingsActivity))
        setPadding(0, dp(3), dp(8), 0)
    }
    private fun textButton(value: String, click: () -> Unit) = TextView(this).apply {
        text = value
        gravity = Gravity.CENTER
        textSize = 30f
        setTextColor(android.graphics.Color.WHITE)
        setOnClickListener { click() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
