package com.forgemanager.app.core.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ShellEscaperTest {
    @Test fun quotesMetacharactersAndApostrophe() {
        assertEquals("'a'\\''b;\$(x)`y'", ShellEscaper.quote("a'b;\$(x)`y"))
    }

    @Test fun rejectsNul() {
        assertThrows(IllegalArgumentException::class.java) { ShellEscaper.quote("a\u0000b") }
    }
}
