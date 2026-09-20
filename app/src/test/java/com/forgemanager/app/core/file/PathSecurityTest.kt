package com.forgemanager.app.core.file

import com.forgemanager.app.core.security.PathSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class PathSecurityTest {
    @Test fun acceptsUnicodeAndSpaces() {
        PathSecurity.validateName("relatório 😎 final.txt")
    }

    @Test fun rejectsTraversalAndSeparators() {
        listOf("..", ".", "../x", "a/b", "x\u0000y", "").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { PathSecurity.validateName(value) }
        }
    }

    @Test fun resolvesOnlyDirectChild() {
        val root = createTempDir(prefix = "forge-")
        try { assertEquals(root.canonicalFile, PathSecurity.resolveChild(root, "child").parentFile) }
        finally { root.deleteRecursively() }
    }
}
