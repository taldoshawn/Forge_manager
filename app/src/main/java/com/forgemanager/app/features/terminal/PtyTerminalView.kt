package com.forgemanager.app.features.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class PtyTerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(225, 232, 240)
        textSize = 13f * resources.displayMetrics.scaledDensity
        typeface = Typeface.MONOSPACE
    }
    private val backgroundPaint = Paint().apply { color = Color.BLACK }
    private var cellWidth = paint.measureText("M").coerceAtLeast(1f)
    private var cellHeight = (paint.fontMetrics.descent - paint.fontMetrics.ascent).coerceAtLeast(1f)
    private var baselineOffset = -paint.fontMetrics.ascent
    private var rows = 24
    private var cols = 80
    private var screen = Array(rows) { CharArray(cols) { ' ' } }
    private var row = 0
    private var col = 0
    private var savedRow = 0
    private var savedCol = 0
    private val escape = StringBuilder()
    private var inEscape = false
    private var escapeSimple = false
    var onTerminalResize: ((Int, Int) -> Unit)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.BLACK)
    }

    @Synchronized
    fun write(value: String) {
        value.forEach(::feed)
        postInvalidateOnAnimation()
    }

    @Synchronized
    fun clearTerminal() {
        screen = Array(rows) { CharArray(cols) { ' ' } }
        row = 0
        col = 0
        invalidate()
    }

    private fun feed(ch: Char) {
        if (inEscape) {
            parseEscape(ch)
            return
        }
        when (ch) {
            '\u001b' -> { inEscape = true; escape.setLength(0); escapeSimple = true }
            '\r' -> col = 0
            '\n' -> newline()
            '\b' -> if (col > 0) col--
            '\t' -> repeat((8 - (col % 8)).coerceAtLeast(1)) { put(' ') }
            else -> if (ch >= ' ' && ch != '\u007f') put(ch)
        }
    }

    private fun parseEscape(ch: Char) {
        if (escapeSimple) {
            escapeSimple = false
            when (ch) {
                '[' -> return
                'c' -> { reset(); inEscape = false; return }
                '7' -> { savedRow = row; savedCol = col; inEscape = false; return }
                '8' -> { row = savedRow.coerceIn(0, rows - 1); col = savedCol.coerceIn(0, cols - 1); inEscape = false; return }
                else -> { inEscape = false; return }
            }
        }
        escape.append(ch)
        if (ch in '@'..'~') {
            executeCsi(escape.toString())
            inEscape = false
            escape.setLength(0)
        } else if (escape.length > 64) {
            inEscape = false
            escape.setLength(0)
        }
    }

    private fun executeCsi(seq: String) {
        if (seq.isEmpty()) return
        val command = seq.last()
        val body = seq.dropLast(1).removePrefix("?").removePrefix(">")
        val args = body.split(';').map { it.toIntOrNull() ?: 0 }
        fun arg(index: Int, default: Int = 1): Int = args.getOrNull(index)?.takeIf { it != 0 } ?: default
        when (command) {
            'A' -> row = (row - arg(0)).coerceAtLeast(0)
            'B' -> row = (row + arg(0)).coerceAtMost(rows - 1)
            'C' -> col = (col + arg(0)).coerceAtMost(cols - 1)
            'D' -> col = (col - arg(0)).coerceAtLeast(0)
            'E' -> { row = (row + arg(0)).coerceAtMost(rows - 1); col = 0 }
            'F' -> { row = (row - arg(0)).coerceAtLeast(0); col = 0 }
            'G' -> col = (arg(0) - 1).coerceIn(0, cols - 1)
            'H', 'f' -> {
                row = (arg(0) - 1).coerceIn(0, rows - 1)
                col = (arg(1) - 1).coerceIn(0, cols - 1)
            }
            'd' -> row = (arg(0) - 1).coerceIn(0, rows - 1)
            'J' -> eraseDisplay(args.firstOrNull() ?: 0)
            'K' -> eraseLine(args.firstOrNull() ?: 0)
            's' -> { savedRow = row; savedCol = col }
            'u' -> { row = savedRow.coerceIn(0, rows - 1); col = savedCol.coerceIn(0, cols - 1) }
            'P' -> deleteChars(arg(0))
            '@' -> insertChars(arg(0))
            'm', 'h', 'l', 'r', 'n', 't' -> Unit
        }
    }

    private fun put(ch: Char) {
        if (col >= cols) newline()
        screen[row][col] = ch
        col++
        if (col >= cols) {
            col = 0
            newline()
        }
    }

    private fun newline() {
        row++
        if (row >= rows) {
            for (r in 1 until rows) screen[r].copyInto(screen[r - 1])
            screen[rows - 1].fill(' ')
            row = rows - 1
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            2, 3 -> screen.forEach { it.fill(' ') }
            1 -> {
                for (r in 0 until row) screen[r].fill(' ')
                for (c in 0..col.coerceAtMost(cols - 1)) screen[row][c] = ' '
            }
            else -> {
                for (c in col until cols) screen[row][c] = ' '
                for (r in row + 1 until rows) screen[r].fill(' ')
            }
        }
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            2 -> screen[row].fill(' ')
            1 -> for (c in 0..col.coerceAtMost(cols - 1)) screen[row][c] = ' '
            else -> for (c in col until cols) screen[row][c] = ' '
        }
    }

    private fun deleteChars(count: Int) {
        val n = count.coerceIn(1, cols - col)
        for (c in col until cols - n) screen[row][c] = screen[row][c + n]
        for (c in cols - n until cols) screen[row][c] = ' '
    }

    private fun insertChars(count: Int) {
        val n = count.coerceIn(1, cols - col)
        for (c in cols - 1 downTo col + n) screen[row][c] = screen[row][c - n]
        for (c in col until col + n) screen[row][c] = ' '
    }

    private fun reset() {
        screen.forEach { it.fill(' ') }
        row = 0
        col = 0
        savedRow = 0
        savedCol = 0
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val newCols = max(10, (w / cellWidth).toInt())
        val newRows = max(2, (h / cellHeight).toInt())
        if (newCols == cols && newRows == rows) return
        val old = screen
        val copyRows = minOf(rows, newRows)
        val copyCols = minOf(cols, newCols)
        rows = newRows
        cols = newCols
        screen = Array(rows) { CharArray(cols) { ' ' } }
        for (r in 0 until copyRows) old[r].copyInto(screen[r], endIndex = copyCols)
        row = row.coerceIn(0, rows - 1)
        col = col.coerceIn(0, cols - 1)
        onTerminalResize?.invoke(rows, cols)
    }

    @Synchronized
    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        for (r in 0 until rows) {
            val line = String(screen[r]).trimEnd()
            if (line.isNotEmpty()) canvas.drawText(line, 0f, r * cellHeight + baselineOffset, paint)
        }
    }

    fun rows() = rows
    fun cols() = cols
}
