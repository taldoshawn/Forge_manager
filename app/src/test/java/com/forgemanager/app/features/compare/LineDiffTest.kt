package com.forgemanager.app.features.compare

import org.junit.Assert.assertEquals
import org.junit.Test

class LineDiffTest {
    @Test fun detectsAddedAndRemovedLines() {
        val diff = LineDiff.compare(listOf("a", "b", "c"), listOf("a", "x", "c", "d"))
        assertEquals(
            listOf(DiffKind.SAME, DiffKind.REMOVED, DiffKind.ADDED, DiffKind.SAME, DiffKind.ADDED),
            diff.map { it.kind }
        )
        assertEquals("b", diff[1].text)
        assertEquals("x", diff[2].text)
    }

    @Test fun identicalInputStaysSame() {
        val diff = LineDiff.compare(listOf("one", "two"), listOf("one", "two"))
        assertEquals(listOf(DiffKind.SAME, DiffKind.SAME), diff.map { it.kind })
    }
}
