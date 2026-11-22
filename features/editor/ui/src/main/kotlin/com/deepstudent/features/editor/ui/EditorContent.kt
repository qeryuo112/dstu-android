package com.deepstudent.features.editor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.deepstudent.features.editor.api.editing.EditCommand

/** Reusable note-content component. The host owns persistence, session identity and replacement. */
@Composable
fun EditorContent(controller: EditorController, modifier: Modifier = Modifier, enabled: Boolean = true) {
    DisposableEffect(controller) { onDispose { controller.finishComposition() } }
    Column(modifier) {
        EditorHistoryBar(controller, enabled)
        BasicTextField(
            value = controller.state.asTextFieldValue(),
            onValueChange = controller::accept,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                keyboardType = KeyboardType.Text,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("editor-input")
                .semantics { contentDescription = "笔记正文" }
                .onFocusChanged { if (!it.isFocused && controller.state.composition != null) controller.finishComposition() }
                .onPreviewKeyEvent { event ->
                    val command = historyShortcut(event, enabled) ?: return@onPreviewKeyEvent false
                    controller.dispatch(command)
                    true
                }.padding(12.dp),
        )
    }
}

private fun historyShortcut(event: KeyEvent, enabled: Boolean): EditCommand? {
    if (!enabled || event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return null
    return when (event.key) {
        Key.Z -> if (event.isShiftPressed) EditCommand.Redo else EditCommand.Undo
        Key.Y -> EditCommand.Redo
        else -> null
    }
}

@Composable
private fun EditorHistoryBar(controller: EditorController, enabled: Boolean) {
    Row(Modifier.fillMaxWidth()) {
        IconButton(
            onClick = { controller.dispatch(EditCommand.Undo) },
            enabled = enabled && controller.state.canUndo,
            modifier = Modifier.testTag("editor-undo"),
        ) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "撤销") }
        IconButton(
            onClick = { controller.dispatch(EditCommand.Redo) },
            enabled = enabled && controller.state.canRedo,
            modifier = Modifier.testTag("editor-redo"),
        ) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "重做") }
    }
}
