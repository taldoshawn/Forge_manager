package com.forgemanager.app.features.editor

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.EditText
import com.forgemanager.app.features.settings.UiPreferences
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

/**
 * Compact MT-style code editor surface.
 *
 * The pinch zoom is deliberately throttled. TextView relayout is expensive for
 * large documents, so applying every tiny ScaleGestureDetector delta causes
 * visible jank. We update at most once per ~48 ms and quantize to quarter-sp.
 */
class MtLineNumberEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : EditText(context, attrs) {

    var onSelectionChangedListener: ((start: Int, end: Int) -> Unit)? = null
    var onZoomStateChanged: ((zooming: Boolean) -> Unit)? = null
    var onZoomFinished: ((sizeSp: Float) -> Unit)? = null

    private var requestedLineConsumed = false
    private var fastDragging = false
    private var editorTextSp = 13f
    private var pendingTextSp = editorTextSp
    private var lastZoomApplyMs = 0L
    private var zooming = false

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(135, 135, 135)
        textSize = lineNumberSizePx()
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.MONOSPACE
    }
    private val fastTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(30, 120, 120, 120)
    }
    private val fastThumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(45, 140, 255)
    }

    private val gutter = dp(38)
    private val fastTouchWidth = dp(16)
    private val fastRailWidth = (resources.displayMetrics.density * 2f).coerceAtLeast(2f)
    private val fastRailInset = dp(5)

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                fastDragging = false
                zooming = true
                pendingTextSp = editorTextSp
                lastZoomApplyMs = 0L
                parent?.requestDisallowInterceptTouchEvent(true)
                onZoomStateChanged?.invoke(true)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pendingTextSp = quantize(
                    (pendingTextSp * detector.scaleFactor).coerceIn(MIN_TEXT_SP, MAX_TEXT_SP)
                )
                val now = SystemClock.uptimeMillis()
                if (now - lastZoomApplyMs >= ZOOM_FRAME_MS && abs(pendingTextSp - editorTextSp) >= 0.24f) {
                    applyZoom(pendingTextSp)
                    lastZoomApplyMs = now
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (abs(pendingTextSp - editorTextSp) >= 0.01f) applyZoom(pendingTextSp)
                zooming = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onZoomStateChanged?.invoke(false)
                onZoomFinished?.invoke(editorTextSp)
            }
        }
    )

    init {
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, editorTextSp)
        setTextColor(Color.rgb(32, 32, 32))
        setBackgroundColor(Color.TRANSPARENT)
        // No visible frame/divider: only whitespace separates gutter and content.
        setPadding(gutter, dp(6), dp(22), dp(8))
        gravity = android.view.Gravity.TOP or android.view.Gravity.START
        setHorizontallyScrolling(true)
        includeFontPadding = false
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
    }

    fun applyPalette(background: Int, text: Int, gutterText: Int) {
        setBackgroundColor(background)
        setTextColor(text)
        linePaint.color = gutterText
        fastTrackPaint.color = Color.argb(
            if (UiPreferences.isLight(context)) 24 else 52,
            Color.red(gutterText), Color.green(gutterText), Color.blue(gutterText)
        )
        fastThumbPaint.color = UiPreferences.accent(context)
        invalidate()
    }

    fun setWordWrapEnabled(enabled: Boolean) {
        setHorizontallyScrolling(!enabled)
        maxLines = Int.MAX_VALUE
        requestLayout()
    }

    fun setEditorZoomSp(value: Float) {
        pendingTextSp = quantize(value.coerceIn(MIN_TEXT_SP, MAX_TEXT_SP))
        applyZoom(pendingTextSp)
        onZoomFinished?.invoke(editorTextSp)
    }

    fun editorZoomSp(): Float = editorTextSp
    fun isZooming(): Boolean = zooming

    private fun applyZoom(value: Float) {
        val next = value.coerceIn(MIN_TEXT_SP, MAX_TEXT_SP)
        if (abs(next - editorTextSp) < 0.01f) return
        editorTextSp = next
        // setTextSize already invalidates/requests layout; avoid an additional
        // requestLayout() here because it doubles layout work during pinch.
        setTextSize(TypedValue.COMPLEX_UNIT_SP, editorTextSp)
        linePaint.textSize = lineNumberSizePx()
        postInvalidateOnAnimation()
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        if (!requestedLineConsumed && !text.isNullOrEmpty()) post(::consumeRequestedLine)
        if (!zooming) postInvalidateOnAnimation()
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!zooming) onSelectionChangedListener?.invoke(selStart, selEnd)
    }

    override fun onDraw(canvas: Canvas) {
        val currentLayout = layout
        if (currentLayout != null && currentLayout.lineCount > 0) {
            val first = currentLayout.getLineForVertical(scrollY.coerceAtLeast(0))
            val last = currentLayout.getLineForVertical((scrollY + height).coerceAtLeast(0))
                .coerceAtMost(currentLayout.lineCount - 1)
            // scrollX is included so line numbers stay visually fixed while code
            // moves horizontally.
            val numberX = scrollX + gutter - dp(7).toFloat()
            for (line in first..last) {
                canvas.drawText(
                    (line + 1).toString(),
                    numberX,
                    currentLayout.getLineBaseline(line).toFloat(),
                    linePaint
                )
            }
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
        val trackTop = visibleTop + dp(5)
        val trackBottom = visibleBottom - dp(5)
        val available = (trackBottom - trackTop).coerceAtLeast(1f)
        val layoutHeight = (layout?.height ?: height).coerceAtLeast(1)
        val viewport = (height - compoundPaddingTop - compoundPaddingBottom).coerceAtLeast(1)
        val thumbHeight = max(dp(26).toFloat(), available * viewport / layoutHeight.toFloat())
            .coerceAtMost(available)
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
        val top = dp(5).toFloat()
        val bottom = (height - dp(5)).toFloat().coerceAtLeast(top + 1f)
        val fraction = ((localY - top) / (bottom - top)).coerceIn(0f, 1f)
        scrollTo(scrollX, (fraction * maxScroll).toInt())
        postInvalidateOnAnimation()
    }

    private fun maxScrollY(): Int {
        val currentLayout = layout ?: return 0
        val viewport = (height - compoundPaddingTop - compoundPaddingBottom).coerceAtLeast(0)
        return (currentLayout.height - viewport).coerceAtLeast(0)
    }

    private fun lineNumberSizePx(): Float =
        (editorTextSp * 0.75f).coerceIn(8.3f, 22f) * resources.displayMetrics.scaledDensity

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

    private fun quantize(value: Float): Float = round(value * 4f) / 4f
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_REQUESTED_LINE = "requested_line"
        private const val MIN_TEXT_SP = 9f
        private const val MAX_TEXT_SP = 30f
        private const val ZOOM_FRAME_MS = 48L
    }
}
