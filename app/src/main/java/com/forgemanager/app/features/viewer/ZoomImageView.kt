package com.forgemanager.app.features.viewer

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {
    private val drawMatrix = Matrix()
    private var minimumScale = 1f
    private var maximumScale = 8f
    private var pendingFit = true

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val current = matrixScale()
            val wanted = (current * detector.scaleFactor).coerceIn(minimumScale, maximumScale)
            val factor = if (current == 0f) 1f else wanted / current
            drawMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
            constrain()
            imageMatrix = drawMatrix
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (matrixScale() <= minimumScale * 1.001f) return false
            drawMatrix.postTranslate(-distanceX, -distanceY)
            constrain()
            imageMatrix = drawMatrix
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (matrixScale() > minimumScale * 1.25f) {
                fitToScreen()
            } else {
                val factor = ((minimumScale * 2.5f) / matrixScale()).coerceAtLeast(1f)
                drawMatrix.postScale(factor, factor, e.x, e.y)
                constrain()
                imageMatrix = drawMatrix
            }
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        pendingFit = true
        post { fitToScreen() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && (pendingFit || oldw != w || oldh != h)) fitToScreen()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
        val scaled = scaler.onTouchEvent(event)
        val gestured = gestures.onTouchEvent(event)
        return scaled || gestured || super.onTouchEvent(event)
    }

    fun fitToScreen() {
        val d = drawable ?: return
        if (width <= 0 || height <= 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        val contentW = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val contentH = (height - paddingTop - paddingBottom).coerceAtLeast(1)
        val scale = min(contentW.toFloat() / d.intrinsicWidth, contentH.toFloat() / d.intrinsicHeight)
        minimumScale = scale.coerceAtLeast(0.0001f)
        maximumScale = minimumScale * 8f
        val dx = paddingLeft + (contentW - d.intrinsicWidth * minimumScale) / 2f
        val dy = paddingTop + (contentH - d.intrinsicHeight * minimumScale) / 2f
        drawMatrix.reset()
        drawMatrix.postScale(minimumScale, minimumScale)
        drawMatrix.postTranslate(dx, dy)
        imageMatrix = drawMatrix
        pendingFit = false
    }

    fun resetZoom() = fitToScreen()

    fun rotateClockwise() {
        rotation = (rotation + 90f) % 360f
        post { fitToScreen() }
    }

    private fun matrixScale(): Float {
        val values = FloatArray(9)
        drawMatrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun constrain() {
        val d = drawable ?: return
        val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        drawMatrix.mapRect(rect)
        val leftBound = paddingLeft.toFloat()
        val topBound = paddingTop.toFloat()
        val rightBound = (width - paddingRight).toFloat()
        val bottomBound = (height - paddingBottom).toFloat()
        val viewW = rightBound - leftBound
        val viewH = bottomBound - topBound
        var dx = 0f
        var dy = 0f
        if (rect.width() <= viewW) dx = leftBound + (viewW - rect.width()) / 2f - rect.left
        else if (rect.left > leftBound) dx = leftBound - rect.left
        else if (rect.right < rightBound) dx = rightBound - rect.right
        if (rect.height() <= viewH) dy = topBound + (viewH - rect.height()) / 2f - rect.top
        else if (rect.top > topBound) dy = topBound - rect.top
        else if (rect.bottom < bottomBound) dy = bottomBound - rect.bottom
        drawMatrix.postTranslate(dx, dy)
    }
}
