package com.forgemanager.app.features.compare

enum class DiffKind { SAME, ADDED, REMOVED }

data class DiffLine(
    val kind: DiffKind,
    val leftNumber: Int?,
    val rightNumber: Int?,
    val text: String
)

object LineDiff {
    private const val MAX_MATRIX_CELLS = 2_250_000L

    fun compare(left: List<String>, right: List<String>): List<DiffLine> {
        if (left.size.toLong() * right.size.toLong() > MAX_MATRIX_CELLS) {
            return compareLinear(left, right)
        }

        val cols = right.size + 1
        val table = IntArray((left.size + 1) * cols)
        fun index(i: Int, j: Int) = i * cols + j

        for (i in left.size - 1 downTo 0) {
            for (j in right.size - 1 downTo 0) {
                table[index(i, j)] = if (left[i] == right[j]) {
                    table[index(i + 1, j + 1)] + 1
                } else {
                    maxOf(table[index(i + 1, j)], table[index(i, j + 1)])
                }
            }
        }

        val result = ArrayList<DiffLine>(left.size + right.size)
        var i = 0
        var j = 0
        while (i < left.size && j < right.size) {
            when {
                left[i] == right[j] -> {
                    result += DiffLine(DiffKind.SAME, i + 1, j + 1, left[i])
                    i++; j++
                }
                table[index(i + 1, j)] >= table[index(i, j + 1)] -> {
                    result += DiffLine(DiffKind.REMOVED, i + 1, null, left[i])
                    i++
                }
                else -> {
                    result += DiffLine(DiffKind.ADDED, null, j + 1, right[j])
                    j++
                }
            }
        }
        while (i < left.size) {
            result += DiffLine(DiffKind.REMOVED, i + 1, null, left[i])
            i++
        }
        while (j < right.size) {
            result += DiffLine(DiffKind.ADDED, null, j + 1, right[j])
            j++
        }
        return result
    }

    /**
     * Memory-safe fallback for extremely large inputs. It intentionally avoids an
     * O(n*m) matrix and reports line-by-line replacements as remove + add pairs.
     */
    private fun compareLinear(left: List<String>, right: List<String>): List<DiffLine> {
        val result = ArrayList<DiffLine>(left.size + right.size)
        val common = minOf(left.size, right.size)
        for (i in 0 until common) {
            if (left[i] == right[i]) {
                result += DiffLine(DiffKind.SAME, i + 1, i + 1, left[i])
            } else {
                result += DiffLine(DiffKind.REMOVED, i + 1, null, left[i])
                result += DiffLine(DiffKind.ADDED, null, i + 1, right[i])
            }
        }
        for (i in common until left.size) result += DiffLine(DiffKind.REMOVED, i + 1, null, left[i])
        for (i in common until right.size) result += DiffLine(DiffKind.ADDED, null, i + 1, right[i])
        return result
    }
}
