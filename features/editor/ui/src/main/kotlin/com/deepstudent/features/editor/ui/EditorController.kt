package com.deepstudent.features.editor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.deepstudent.features.editor.api.SourceRange
import com.deepstudent.features.editor.api.editing.EditCommand
import com.deepstudent.features.editor.api.editing.EditResult
import com.deepstudent.features.editor.api.editing.EditorInput
import com.deepstudent.features.editor.api.editing.EditorSession
import com.deepstudent.features.editor.api.editing.EditorState
import com.deepstudent.features.editor.api.editing.Selection

/**
 * Main-thread adapter. The host keeps a session stable for a draft and creates a new one on explicit
 * replacement/reload. Finishing a session composition closes its history group, not the IME candidate UI.
 */
class EditorController(
    private val session: EditorSession,
    private val onSourceChanged: (String) -> Unit,
) {
    var state: EditorState by mutableStateOf(session.state, neverEqualPolicy())
        private set

    fun dispatch(command: EditCommand): EditResult {
        val previous = state.source
        val result = session.dispatch(command)
        state = result.state
        if (state.source != previous) onSourceChanged(state.source)
        return result
    }

    fun finishComposition() { dispatch(EditCommand.FinishComposition) }

    internal fun accept(value: TextFieldValue) {
        val selection = Selection(value.selection.start, value.selection.end)
        val composition = value.composition?.let { SourceRange(it.min, it.max) }
        dispatch(EditCommand.Input(state.source, EditorInput(value.text, selection, composition)))
    }
}

@Composable
fun rememberEditorController(session: EditorSession, onSourceChanged: (String) -> Unit): EditorController {
    val callback = rememberUpdatedState(onSourceChanged)
    return remember(session) { EditorController(session) { callback.value(it) } }
}

internal fun EditorState.asTextFieldValue(): TextFieldValue = TextFieldValue(
    text = source,
    selection = TextRange(selection.anchor, selection.focus),
    composition = composition?.let { TextRange(it.start, it.endExclusive) },
)
