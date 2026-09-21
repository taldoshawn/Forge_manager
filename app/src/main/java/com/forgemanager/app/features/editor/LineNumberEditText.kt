package com.forgemanager.app.features.editor

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.EditText
import com.forgemanager.app.features.settings.UiPreferences
import kotlin.math.max

class LineNumberEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : EditText(context, attrs) {
    var onSelectionChangedListener: ((start: Int, end: Int) -> Unit)? = null
    private var requestedLineConsumed = false
    private var fastDragging = false
    private var editorTextSp = 13f

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(100, 116, 139)
        textSize = lineNumberSizePx()
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.MONOSPACE
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(45, 55, 72)
        strokeWidth = resources.displayMetrics.density
    }
    private val fastTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(42, 110, 120, 135)
    }
    private val fastThumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(31, 126, 232)
    }
    private val gutter = (40 * resources.displayMetrics.density).toInt()
    private val fastTouchWidth = (14 * resources.displayMetrics.density).toInt()
    private val fastRailWidth = (2 * resources.displayMetrics.density).coerceAtLeast(2f)
    private val fastRailInset = (6 * resources.displayMetrics.density).toInt()

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            fastDragging = false
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val next = (editorTextSp * detector.scaleFactor).coerceIn(MIN_TEXT_SP, MAX_TEXT_SP)
            if (kotlin.math.abs(next - editorTextSp) < 0.03f) return true
            editorTextSp = next
            setTextSize(TypedValue.COMPLEX_UNIT_SP, editorTextSp)
            linePaint.textSize = lineNumberSizePx()
            requestLayout()
            invalidate()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
    })

    init {
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, editorTextSp)
        setTextColor(Color.rgb(226, 232, 240))
        setBackgroundColor(Color.rgb(15, 18, 24))
        // Reserve a real lane on the right so the fast-scroll thumb never covers code.
        setPadding(gutter, dp(7), dp(26), dp(10))
        gravity = android.view.Gravity.TOP or android.view.Gravity.START
        setHorizontallyScrolling(true)
        includeFontPadding = false
        isVerticalScrollBarEnabled = false
    }

    fun applyPalette(background: Int, text: Int, gutterText: Int, divider: Int) {
        setBackgroundColor(background)
        setTextColor(text)
        linePaint.color = gutterText
        dividerPaint.color = divider
        fastTrackPaint.color = Color.argb(
            if (UiPreferences.isLight(context)) 35 else 65,
            Color.red(divider), Color.green(divider), Color.blue(divider)
        )
        fastThumbPaint.color = UiPreferences.accent(context)
        invalidate()
    }

    fun setWordWrapEnabled(enabled: Boolean) {
        setHorizontallyScrolling(!enabled)
        maxLines = Int.MAX_VALUE
        invalidate()
    }

    fun setEditorZoomSp(value: Float) {
        editorTextSp = value.coerceIn(MIN_TEXT_SP, MAX_TEXT_SP)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, editorTextSp)
        linePaint.textSize = lineNumberSizePx()
        requestLayout()
        invalidate()
    }

    fun editorZoomSp(): Float = editorTextSp

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        if (!requestedLineConsumed && !text.isNullOrEmpty()) post(::consumeRequestedLine)
        postInvalidateOnAnimation()
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
                canvas.drawText((line + 1).toString(), gutter - dp(8).toFloat(), baseline, linePaint)
            }
            val x = gutter - dp(3).toFloat()
            canvas.drawLine(x, scrollY.toFloat(), x, (scrollY + height).toFloat(), dividerPaint)
        }
        super.onDraw(canvas)
        drawFastScroller(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress || event.pointerCount > 1) {
            fastDragging = false
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        val hitRail = event.x >= width - fastTouchWidth
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (hitRail && maxScrollY() > 0) {
                    fastDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    fastScrollTo(event.y)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (fastDragging) {
                    fastScrollTo(event.y)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (fastDragging) {
                    fastScrollTo(event.y)
                    fastDragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    performClick()
                    return true
                }
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun drawFastScroller(canvas: Canvas) {
        val maxScroll = maxScrollY()
        if (maxScroll <= 0 || height <= 0) return
        val visibleTop = scrollY.toFloat()
        val visibleBottom = visibleTop + height
        val right = scrollX + width - fastRailInset.toFloat()
        val left = right - fastRailWidth
        val trackTop = visibleTop + dp(6)
        val trackBottom = visibleBottom - dp(6)
        val available = (trackBottom - trackTop).coerceAtLeast(1f)
        val layoutHeight = (layout?.height ?: height).coerceAtLeast(1)
        val viewport = (height - compoundPaddingTop - compoundPaddingBottom).coerceAtLeast(1)
        val thumbHeight = max(dp(28).toFloat(), available * viewport / layoutHeight.toFloat()).coerceAtMost(available)
        val fraction = (scrollY.toFloat() / maxScroll.toFloat()).coerceIn(0f, 1f)
        val thumbTop = trackTop + (available - thumbHeight) * fraction
        val radius = fastRailWidth
        canvas.drawRoundRect(RectF(left, trackTop, right, trackBottom), radius, radius, fastTrackPaint)
        canvas.drawRoundRect(
            RectF(left - dp(1), thumbTop, right + dp(1), thumbTop + thumbHeight),
            radius * 1.5f,
            radius * 1.5f,
            fastThumbPaint
        )
    }

    private fun fastScrollTo(localY: Float) {
        val maxScroll = maxScrollY()
        if (maxScroll <= 0) return
        val top = dp(6).toFloat()
        val bottom = (height - dp(6)).toFloat().coerceAtLeast(top + 1f)
        val fraction = ((localY - top) / (bottom - top)).coerceIn(0f, 1f)
        scrollTo(scrollX, (fraction * maxScroll).toInt())
        invalidate()
    }

    private fun maxScrollY(): Int {
        val currentLayout = layout ?: return 0
        val viewport = (height - compoundPaddingTop - compoundPaddingBottom).coerceAtLeast(0)
        return (currentLayout.height - viewport).coerceAtLeast(0)
    }

    private fun lineNumberSizePx(): Float =
        (editorTextSp * 0.78f).coerceIn(8.5f, 24f) * resources.displayMetrics.scaledDensity

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
        private const val MIN_TEXT_SP = 9f
        private const val MAX_TEXT_SP = 34f
    }
}
