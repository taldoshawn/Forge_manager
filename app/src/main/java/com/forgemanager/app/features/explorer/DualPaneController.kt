package com.forgemanager.app.features.explorer

import com.forgemanager.app.core.file.FileLocation
import com.forgemanager.app.core.file.FileNode
import java.util.ArrayDeque

enum class PaneId { LEFT, RIGHT }
enum class SortField { NAME, SIZE, DATE, TYPE }

data class PaneState(
    var current: FileLocation,
    val backStack: ArrayDeque<FileLocation> = ArrayDeque(),
    val forwardStack: ArrayDeque<FileLocation> = ArrayDeque(),
    var items: List<FileNode> = emptyList(),
    val selection: MutableSet<String> = linkedSetOf(),
    var sortField: SortField = SortField.NAME,
    var ascending: Boolean = true,
    var filter: String = "",
    var showHidden: Boolean = true,
    var scrollPosition: Int = 0
) {
    fun selectedItems(): List<FileNode> = items.filter { it.location.displayPath in selection }
}

class DualPaneController(initialLeft: FileLocation, initialRight: FileLocation) {
    val leftPane = PaneState(initialLeft)
    val rightPane = PaneState(initialRight)
    var activePane: PaneId = PaneId.LEFT
        private set

    fun pane(id: PaneId = activePane): PaneState = if (id == PaneId.LEFT) leftPane else rightPane
    fun otherPane(): PaneState = if (activePane == PaneId.LEFT) rightPane else leftPane
    fun activate(id: PaneId) { activePane = id }

    fun navigate(id: PaneId, destination: FileLocation, recordHistory: Boolean = true) {
        val pane = pane(id)
        if (recordHistory && pane.current != destination) {
            pane.backStack.addLast(pane.current)
            pane.forwardStack.clear()
        }
        pane.current = destination
        pane.selection.clear()
        pane.scrollPosition = 0
    }

    fun back(id: PaneId = activePane): FileLocation? {
        val pane = pane(id)
        val target = pane.backStack.removeLastOrNull() ?: return null
        pane.forwardStack.addLast(pane.current)
        pane.current = target
        pane.selection.clear()
        return target
    }

    fun forward(id: PaneId = activePane): FileLocation? {
        val pane = pane(id)
        val target = pane.forwardStack.removeLastOrNull() ?: return null
        pane.backStack.addLast(pane.current)
        pane.current = target
        pane.selection.clear()
        return target
    }

    fun syncFromActive() {
        val targetId = if (activePane == PaneId.LEFT) PaneId.RIGHT else PaneId.LEFT
        navigate(targetId, pane().current)
    }

    fun toggleSelection(node: FileNode) {
        val selection = pane().selection
        if (!selection.add(node.location.displayPath)) selection.remove(node.location.displayPath)
    }

    fun selectAll() {
        pane().selection.apply { clear(); addAll(pane().items.map { it.location.displayPath }) }
    }

    fun invertSelection() {
        val pane = pane()
        val next = pane.items.map { it.location.displayPath }.filterNot { it in pane.selection }
        pane.selection.apply { clear(); addAll(next) }
    }

    fun selectSameType(reference: FileNode) {
        val extension = reference.name.substringAfterLast('.', "").lowercase()
        pane().selection.apply {
            clear()
            addAll(pane().items.filter { it.isDirectory == reference.isDirectory &&
                (reference.isDirectory || it.name.substringAfterLast('.', "").lowercase() == extension) }
                .map { it.location.displayPath })
        }
    }
}

private fun <T> ArrayDeque<T>.removeLastOrNull(): T? = if (isEmpty()) null else removeLast()
