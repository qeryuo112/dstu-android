package com.deepstudent.features.editor.ui

import com.deepstudent.features.editor.api.editing.EditCommand
import com.deepstudent.features.editor.api.editing.EditResult
import com.deepstudent.features.editor.api.editing.EditorSession
import com.deepstudent.features.editor.api.editing.EditorState
import com.deepstudent.features.editor.api.editing.Selection

internal class FakeEditorSession(source: String = "") : EditorSession {
    override var state = EditorState(source, Selection(0, 0))
        private set
    val commands = mutableListOf<EditCommand>()
    private var original = source

    override fun selectedText(): String = state.selection.range.textOf(state.source)

    override fun dispatch(command: EditCommand): EditResult {
        commands += command
        when (command) {
            is EditCommand.Input -> {
                val value = command.value
                state = EditorState(value.source, value.selection, value.composition, value.source != original)
            }
            is EditCommand.Select -> state = state.copy(selection = command.selection)
            EditCommand.Undo -> state = state.copy(source = original, selection = Selection(0, 0), canUndo = false)
            EditCommand.FinishComposition -> state = state.copy(composition = null)
            else -> Unit
        }
        return EditResult.Applied(state)
    }
}
