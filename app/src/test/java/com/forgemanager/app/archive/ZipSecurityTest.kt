package com.forgemanager.app.archive

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipSecurityTest {
    @Test fun blocksZipSlipAndAbsolutePaths() {
        listOf("../evil", "a/../../evil", "/etc/passwd", "C:/evil", "a\\..\\evil").forEach {
            assertThrows(IllegalArgumentException::class.java) { ZipSecurity.normalizeEntryName(it) }
        }
    }

    @Test fun allowsUnicodeNestedPath() {
        assertTrue(ZipSecurity.normalizeEntryName("pasta/áudio 😎.txt").startsWith("pasta/"))
    }
}
