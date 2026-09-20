package com.forgemanager.app.features.explorer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTypeClassifierTest {
    @Test fun classifiesCommonFileTypes() {
        assertEquals(FileKind.DIRECTORY, FileTypeClassifier.classify("Download", true))
        assertEquals(FileKind.APK, FileTypeClassifier.classify("app.apk", false))
        assertEquals(FileKind.ARCHIVE, FileTypeClassifier.classify("backup.7z", false))
        assertEquals(FileKind.IMAGE, FileTypeClassifier.classify("photo.webp", false))
        assertEquals(FileKind.IMAGE, FileTypeClassifier.classify("cover.img", false))
        assertEquals(FileKind.CODE, FileTypeClassifier.classify("MainActivity.kt", false))
        assertEquals(FileKind.DEX, FileTypeClassifier.classify("classes.dex", false))
        assertEquals(FileKind.DATABASE, FileTypeClassifier.classify("app.sqlite3", false))
    }

    @Test fun recognizesDiskImageNamesForFallbackInspector() {
        assertTrue(FileTypeClassifier.isDiskImageName("system.img"))
        assertTrue(FileTypeClassifier.isDiskImageName("linux.iso"))
        assertTrue(FileTypeClassifier.isDiskImageName("disk.qcow2"))
    }

    @Test fun hiddenExtensionlessFilesStayGeneric() {
        assertEquals("", FileTypeClassifier.extensionOf(".nomedia"))
        assertEquals(FileKind.GENERIC, FileTypeClassifier.classify(".nomedia", false))
    }
}
