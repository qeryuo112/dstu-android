package com.deepstudent.features.editor.backend.application.editing

import com.deepstudent.features.editor.api.SourceRange
import com.deepstudent.features.editor.api.editing.EditCommand
import com.deepstudent.features.editor.api.editing.EditRejection
import com.deepstudent.features.editor.api.editing.EditResult
import com.deepstudent.features.editor.api.editing.EditorInput
import com.deepstudent.features.editor.api.editing.EditorSession
import com.deepstudent.features.editor.api.editing.EditorSessionFactory
import com.deepstudent.features.editor.api.editing.EditorState
import com.deepstudent.features.editor.api.editing.Selection
import com.deepstudent.features.editor.api.editing.TextEdit

class DefaultEditorSessionFactory(private val historyLimit: Int = 1000) : EditorSessionFactory {
    init { require(historyLimit > 0) }
    override fun create(source: String): EditorSession = DefaultEditorSession(source, historyLimit)
}

class DefaultEditorSession(source: String, historyLimit: Int = 1000) : EditorSession {
    private val history = EditHistory(historyLimit)
    private var current = EditorInput(source, Selection(0, 0), null)
    private var compositionStart: EditorInput? = null

    init {
        require(historyLimit > 0)
        require(validUtf16(source)) { "Invalid UTF-16 source" }
    }

    override val state: EditorState
        get() = EditorState(
            current.source, current.selection, current.composition,
            history.canUndo || compositionStart?.source?.let { it != current.source } == true,
            history.canRedo && (compositionStart == null || compositionStart?.source == current.source),
        )

    override fun selectedText(): String = current.selection.range.textOf(current.source)

    override fun dispatch(command: EditCommand): EditResult = when (command) {
        is EditCommand.Replace -> replace(command.edit, command.selectionAfter)
        is EditCommand.Select -> select(command.selection)
        is EditCommand.Input -> input(command.expectedSource, command.value)
        EditCommand.FinishComposition -> { finishComposition(); applied() }
        EditCommand.Undo -> travel(undo = true)
        EditCommand.Redo -> travel(undo = false)
    }

    private fun replace(edit: TextEdit, selectionAfter: Selection?): EditResult {
        val source = current.source
        val boundaries = GraphemeBoundaries(source)
        if (edit.range.endExclusive > source.length || !boundaries.contains(edit.range)) {
            return rejected(EditRejection.INVALID_RANGE)
        }
        if (edit.range.textOf(source) != edit.expected) return rejected(EditRejection.STALE_SOURCE)
        if (!validUtf16(edit.replacement)) return rejected(EditRejection.INVALID_TEXT)
        val updated = edit.applyTo(source)
        val nextBoundaries = GraphemeBoundaries(updated)
        val nextSelection = selectionAfter ?: Selection(
            nextBoundaries.ceil(edit.range.start + edit.replacement.length),
            nextBoundaries.ceil(edit.range.start + edit.replacement.length),
        )
        if (!selectionInBounds(nextSelection, updated) || !nextBoundaries.contains(nextSelection)) {
            return rejected(EditRejection.INVALID_SELECTION)
        }
        finishComposition()
        val before = current
        current = EditorInput(updated, nextSelection, null)
        history.record(before.source, updated, before.selection, nextSelection)
        return applied()
    }

    private fun select(selection: Selection): EditResult {
        if (!selectionInBounds(selection, current.source)) return rejected(EditRejection.INVALID_SELECTION)
        val normalized = GraphemeBoundaries(current.source).normalize(selection)
        finishComposition()
        current = current.copy(selection = normalized)
        return applied()
    }

    private fun input(expectedSource: String, value: EditorInput): EditResult {
        if (expectedSource != current.source) return rejected(EditRejection.STALE_SOURCE)
        if (!validUtf16(value.source)) return rejected(EditRejection.INVALID_TEXT)
        if (!selectionInBounds(value.selection, value.source)) return rejected(EditRejection.INVALID_SELECTION)
        val composition = value.composition
        if (composition != null && composition.endExclusive > value.source.length) {
            return rejected(EditRejection.INVALID_COMPOSITION)
        }
        if (!safeInputChange(current, value.source)) return rejected(EditRejection.INVALID_RANGE)
        val boundaries = GraphemeBoundaries(value.source)
        val normalized = value.copy(
            selection = boundaries.normalize(value.selection),
            composition = value.composition?.let(boundaries::normalize),
        )
        acceptInput(normalized)
        return applied()
    }

    private fun acceptInput(value: EditorInput) {
        val before = current
        if (value.composition != null && compositionStart == null) compositionStart = before
        current = value
        if (value.composition == null && compositionStart != null) {
            finishComposition()
        } else if (compositionStart != null && before.source != value.source) {
            history.discardRedo()
        } else if (compositionStart == null) {
            history.record(before.source, value.source, before.selection, value.selection)
        }
    }

    private fun finishComposition() {
        val start = compositionStart
        current = current.copy(composition = null)
        compositionStart = null
        if (start != null) history.record(start.source, current.source, start.selection, current.selection)
    }

    private fun travel(undo: Boolean): EditResult {
        finishComposition()
        val entry = if (undo) history.undo() else history.redo()
        if (entry != null) {
            val edit = if (undo) entry.inverse else entry.forward
            val selection = if (undo) entry.beforeSelection else entry.afterSelection
            check(edit.range.textOf(current.source) == edit.expected) { "History source mismatch" }
            current = EditorInput(edit.applyTo(current.source), selection, null)
        }
        return applied()
    }

    private fun applied(): EditResult = EditResult.Applied(state)
    private fun rejected(reason: EditRejection): EditResult = EditResult.Rejected(reason, state)
}

private fun selectionInBounds(selection: Selection, source: String): Boolean =
    selection.anchor <= source.length && selection.focus <= source.length

/** The old selected/composing span may be rewritten as a whole, never a partial grapheme. */
private fun safeInputChange(before: EditorInput, after: String): Boolean {
    if (before.source == after) return true
    val raw = changedSpan(before.source, after)
    val changed = SourceRange(raw.start, raw.oldEnd)
    val boundaries = GraphemeBoundaries(before.source)
    if (boundaries.contains(changed)) return true
    val selected = before.selection.range.takeUnless { before.selection.collapsed }
    return listOfNotNull(selected, before.composition).any {
        it.start <= changed.start && it.endExclusive >= changed.endExclusive && boundaries.contains(it)
    }
}
