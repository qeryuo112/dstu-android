package com.deepstudent.features.editor.backend.application.editing

import com.deepstudent.features.editor.api.editing.Selection
import com.deepstudent.features.editor.api.editing.TextEdit

internal data class HistoryEntry(
    val forward: TextEdit,
    val inverse: TextEdit,
    val beforeSelection: Selection,
    val afterSelection: Selection,
)

internal class EditHistory(private val capacity: Int) {
    private val undo = ArrayDeque<HistoryEntry>()
    private val redo = ArrayDeque<HistoryEntry>()
    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    fun discardRedo() { redo.clear() }

    fun record(before: String, after: String, beforeSelection: Selection, afterSelection: Selection) {
        if (before == after) return
        val change = textChange(before, after)
        undo.addLast(HistoryEntry(change, change.inverse(), beforeSelection, afterSelection))
        if (undo.size > capacity) undo.removeFirst()
        discardRedo()
    }

    fun undo(): HistoryEntry? = undo.removeLastOrNull()?.also { redo.addLast(it) }
    fun redo(): HistoryEntry? = redo.removeLastOrNull()?.also { undo.addLast(it) }
}
