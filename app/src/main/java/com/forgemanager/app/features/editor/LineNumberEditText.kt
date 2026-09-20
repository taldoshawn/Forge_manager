package com.forgemanager.app.features.editor

import android.app.Activity
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
    var onSelectionChangedListener: ((start: Int, end: Int) -> Unit)? = null
    private var requestedLineConsumed = false

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

    fun applyPalette(background: Int, text: Int, gutterText: Int, divider: Int) {
        setBackgroundColor(background)
        setTextColor(text)
        linePaint.color = gutterText
        dividerPaint.color = divider
        invalidate()
    }

    fun setWordWrapEnabled(enabled: Boolean) {
        setHorizontallyScrolling(!enabled)
        maxLines = Int.MAX_VALUE
        invalidate()
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        if (!requestedLineConsumed && !text.isNullOrEmpty()) post(::consumeRequestedLine)
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelectionChangedListener?.invoke(selStart, selEnd)
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

    private fun consumeRequestedLine() {
        if (requestedLineConsumed) return
        val activity = context as? Activity ?: return
        val requested = activity.intent?.getIntExtra(EXTRA_REQUESTED_LINE, -1) ?: -1
        if (requested <= 0) {
            requestedLineConsumed = true
            return
        }
        val content = text ?: return
        var currentLine = 1
        var offset = 0
        while (currentLine < requested && offset < content.length) {
            if (content[offset] == '\n') currentLine++
            offset++
        }
        if (currentLine == requested) {
            setSelection(offset.coerceIn(0, content.length))
            requestFocus()
            bringPointIntoView(offset)
        }
        requestedLineConsumed = true
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_REQUESTED_LINE = "requested_line"
    }
}
