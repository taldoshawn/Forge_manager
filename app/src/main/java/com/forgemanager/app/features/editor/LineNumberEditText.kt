package com.forgemanager.app.features.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.widget.EditText

class LineNumberEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : EditText(context, attrs) {
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(100, 116, 139)
        textSize = 11 * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.MONOSPACE
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(45, 55, 72)
        strokeWidth = resources.displayMetrics.density
    }
    private val gutter = (48 * resources.displayMetrics.density).toInt()

    init {
        typeface = Typeface.MONOSPACE
        setTextSize(14f)
        setTextColor(Color.rgb(226, 232, 240))
        setBackgroundColor(Color.rgb(15, 18, 24))
        setPadding(gutter, dp(10), dp(12), dp(12))
        gravity = android.view.Gravity.TOP or android.view.Gravity.START
        setHorizontallyScrolling(true)
        includeFontPadding = false
    }

    override fun onDraw(canvas: Canvas) {
        val currentLayout = layout
        if (currentLayout != null) {
            val first = currentLayout.getLineForVertical(scrollY)
            val last = currentLayout.getLineForVertical(scrollY + height)
            for (line in first..last) {
                val baseline = currentLayout.getLineBaseline(line).toFloat()
                canvas.drawText((line + 1).toString(), gutter - dp(9).toFloat(), baseline, linePaint)
            }
            val x = gutter - dp(4).toFloat()
            canvas.drawLine(x, scrollY.toFloat(), x, (scrollY + height).toFloat(), dividerPaint)
        }
        super.onDraw(canvas)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
