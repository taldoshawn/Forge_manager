package com.forgemanager.app.features.explorer

import com.forgemanager.app.core.file.FileLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DualPaneControllerTest {
    @Test fun historiesAreIndependent() {
        val controller = DualPaneController(FileLocation.Direct("/left"), FileLocation.Direct("/right"))
        controller.navigate(PaneId.LEFT, FileLocation.Direct("/left/a"))
        controller.navigate(PaneId.RIGHT, FileLocation.Direct("/right/b"))
        assertEquals("/left", (controller.back(PaneId.LEFT) as FileLocation.Direct).path)
        assertEquals("/right/b", (controller.rightPane.current as FileLocation.Direct).path)
        assertEquals("/left/a", (controller.forward(PaneId.LEFT) as FileLocation.Direct).path)
        assertNull(controller.forward(PaneId.RIGHT))
    }

    @Test fun syncPreservesSourceAndChangesOnlyOtherPane() {
        val controller = DualPaneController(FileLocation.Direct("/a"), FileLocation.Direct("/b"))
        controller.activate(PaneId.RIGHT)
        controller.syncFromActive()
        assertEquals("/b", (controller.leftPane.current as FileLocation.Direct).path)
        assertEquals("/b", (controller.rightPane.current as FileLocation.Direct).path)
    }
}
