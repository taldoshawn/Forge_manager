package com.forgemanager.app.features.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** Lightweight bitmap editor used by ImageEditorActivity. */
class ImageEditView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    enum class Tool { PAN, PEN, ERASER }

    private var bitmap: Bitmap? = null
    private var tool = Tool.PAN
    private var brushColor = Color.RED
    private var brushWidthPx = 8f
    private var lastX = 0f
    private var lastY = 0f
    private var drawing = false
    private val history = ArrayDeque<Bitmap>()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun setBitmap(value: Bitmap) {
        history.forEach { if (!it.isRecycled) it.recycle() }
        history.clear()
        bitmap?.takeIf { it !== value && !it.isRecycled }?.recycle()
        bitmap = value.copy(Bitmap.Config.ARGB_8888, true)
        invalidate()
    }

    fun bitmapCopy(): Bitmap? = bitmap?.copy(Bitmap.Config.ARGB_8888, false)

    fun setTool(value: Tool) {
        tool = value
        invalidate()
    }

    fun currentTool(): Tool = tool
    fun setBrushColor(color: Int) { brushColor = color }
    fun setBrushWidthDp(dp: Float) { brushWidthPx = dp * resources.displayMetrics.density }

    fun undo(): Boolean {
        val previous = history.removeLastOrNull() ?: return false
        bitmap?.takeIf { !it.isRecycled }?.recycle()
        bitmap = previous
        invalidate()
        return true
    }

    fun rotateClockwise() {
        val current = bitmap ?: return
        pushHistory()
        val matrix = android.graphics.Matrix().apply { postRotate(90f) }
        bitmap = Bitmap.createBitmap(current, 0, 0, current.width, current.height, matrix, true)
        invalidate()
    }

    fun cropCenter(aspectWidth: Int, aspectHeight: Int) {
        val current = bitmap ?: return
        if (aspectWidth <= 0 || aspectHeight <= 0) return
        val target = aspectWidth.toFloat() / aspectHeight
        val currentRatio = current.width.toFloat() / current.height
        val cropW: Int
        val cropH: Int
        if (currentRatio > target) {
            cropH = current.height
            cropW = (cropH * target).toInt().coerceAtMost(current.width)
        } else {
            cropW = current.width
            cropH = (cropW / target).toInt().coerceAtMost(current.height)
        }
        val left = (current.width - cropW) / 2
        val top = (current.height - cropH) / 2
        pushHistory()
        bitmap = Bitmap.createBitmap(current, left, top, cropW, cropH).copy(Bitmap.Config.ARGB_8888, true)
        invalidate()
    }

    fun trimTransparentBorder() {
        val current = bitmap ?: return
        val pixels = IntArray(current.width * current.height)
        current.getPixels(pixels, 0, current.width, 0, 0, current.width, current.height)
        var minX = current.width
        var minY = current.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until current.height) for (x in 0 until current.width) {
            if (Color.alpha(pixels[y * current.width + x]) > 8) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (maxX < minX || maxY < minY) return
        if (minX == 0 && minY == 0 && maxX == current.width - 1 && maxY == current.height - 1) return
        pushHistory()
        bitmap = Bitmap.createBitmap(current, minX, minY, maxX - minX + 1, maxY - minY + 1).copy(Bitmap.Config.ARGB_8888, true)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = bitmap ?: return
        val rect = destinationRect(current)
        canvas.drawBitmap(current, null, rect, bitmapPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (tool == Tool.PAN) return true
        val current = bitmap ?: return false
        val point = mapToBitmap(event.x, event.y, current) ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pushHistory()
                lastX = point.first
                lastY = point.second
                drawing = true
                drawSegment(current, lastX, lastY, lastX, lastY)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!drawing) return true
                drawSegment(current, lastX, lastY, point.first, point.second)
                lastX = point.first
                lastY = point.second
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drawing = false
                invalidate()
                return true
            }
        }
        return true
    }

    private fun drawSegment(target: Bitmap, x1: Float, y1: Float, x2: Float, y2: Float) {
        brush.strokeWidth = brushWidthPx.coerceAtLeast(1f) * (target.width.toFloat() / width.coerceAtLeast(1))
        if (tool == Tool.ERASER) {
            brush.color = Color.TRANSPARENT
            brush.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        } else {
            brush.color = brushColor
            brush.xfermode = null
        }
        Canvas(target).drawLine(x1, y1, x2, y2, brush)
        brush.xfermode = null
    }

    private fun destinationRect(current: Bitmap): RectF {
        val scale = min(width.toFloat() / current.width, height.toFloat() / current.height).coerceAtLeast(0.0001f)
        val drawW = current.width * scale
        val drawH = current.height * scale
        val left = (width - drawW) / 2f
        val top = (height - drawH) / 2f
        return RectF(left, top, left + drawW, top + drawH)
    }

    private fun mapToBitmap(x: Float, y: Float, current: Bitmap): Pair<Float, Float>? {
        val rect = destinationRect(current)
        if (!rect.contains(x, y)) return null
        val bx = ((x - rect.left) / rect.width() * current.width).coerceIn(0f, current.width - 1f)
        val by = ((y - rect.top) / rect.height() * current.height).coerceIn(0f, current.height - 1f)
        return bx to by
    }

    private fun pushHistory() {
        val current = bitmap ?: return
        history.addLast(current.copy(Bitmap.Config.ARGB_8888, true))
        while (history.size > MAX_HISTORY) {
            history.removeFirst().takeIf { !it.isRecycled }?.recycle()
        }
    }

    companion object {
        private const val MAX_HISTORY = 8
    }
}
