package com.deepstudent.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.deepstudent.app.content.BrowserEntry
import com.deepstudent.app.content.ContentEntry
import com.deepstudent.design.DstuTheme

private enum class WorkspaceEntry(val label: String) {
    Notes("笔记"), Content("内容"), Browser("浏览器"),
}

/** The notes destination must disable its back handler whenever its visibility argument is false. */
@Composable
fun AppWorkspace(modifier: Modifier = Modifier, notesContent: @Composable (visible: Boolean) -> Unit) {
    var selected by remember { mutableStateOf(WorkspaceEntry.Notes) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val select: (WorkspaceEntry) -> Unit = { entry ->
        if (entry != selected) {
            focus.clearFocus(force = true)
            keyboard?.hide()
            selected = entry
        }
    }
    Column(modifier.fillMaxSize()) {
        WorkspaceTabs(selected, select)
        Box(Modifier.weight(1f).fillMaxSize()) {
            NotesLayer(selected == WorkspaceEntry.Notes, notesContent)
            BackHandler(enabled = selected != WorkspaceEntry.Notes) { select(WorkspaceEntry.Notes) }
            when (selected) {
                WorkspaceEntry.Notes -> Unit
                WorkspaceEntry.Content -> ContentEntry()
                WorkspaceEntry.Browser -> BrowserEntry(onClose = { select(WorkspaceEntry.Notes) })
            }
        }
    }
}

@Composable
private fun WorkspaceTabs(selected: WorkspaceEntry, onSelect: (WorkspaceEntry) -> Unit) {
    val colors = DstuTheme.colors
    TabRow(selected.ordinal, containerColor = colors.surface, contentColor = colors.primary) {
        WorkspaceEntry.entries.forEach { entry ->
            Tab(
                selected = selected == entry,
                onClick = { onSelect(entry) },
                modifier = Modifier.testTag("workspace-${entry.name.lowercase()}"),
                text = { Text(entry.label, maxLines = 1, style = MaterialTheme.typography.labelLarge) },
            )
        }
    }
}

@Composable
private fun NotesLayer(visible: Boolean, content: @Composable (Boolean) -> Unit) {
    val modifier = if (visible) Modifier.fillMaxSize() else Modifier
        .clearAndSetSemantics { }
        .layout { measurable, constraints ->
            measurable.measure(constraints)
            layout(0, 0) { }
        }
    Box(modifier) { content(visible) }
}
