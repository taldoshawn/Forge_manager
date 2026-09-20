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
        color = Color.GRAY
        textSize = 11 * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.MONOSPACE
    }
    private val gutter = (46 * resources.displayMetrics.density).toInt()

    init {
        typeface = Typeface.MONOSPACE
        setTextSize(14f)
        setTextColor(Color.rgb(35, 35, 35))
        setBackgroundColor(Color.rgb(250, 250, 250))
        setPadding(gutter, paddingTop, paddingRight, paddingBottom)
        gravity = android.view.Gravity.TOP or android.view.Gravity.START
        setHorizontallyScrolling(true)
    }

    override fun onDraw(canvas: Canvas) {
        val layout = layout
        if (layout != null) {
            val first = layout.getLineForVertical(scrollY)
            val last = layout.getLineForVertical(scrollY + height)
            for (line in first..last) {
                val baseline = layout.getLineBaseline(line).toFloat()
                canvas.drawText((line + 1).toString(), gutter - 8 * resources.displayMetrics.density, baseline, linePaint)
            }
            canvas.drawLine((gutter - 3).toFloat(), scrollY.toFloat(), (gutter - 3).toFloat(), (scrollY + height).toFloat(), linePaint)
        }
        super.onDraw(canvas)
    }
}
