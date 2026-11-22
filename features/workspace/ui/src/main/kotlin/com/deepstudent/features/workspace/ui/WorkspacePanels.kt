package com.deepstudent.features.workspace.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.deepstudent.features.workspace.api.LayoutState
import com.deepstudent.features.workspace.api.PanelInstance
import com.deepstudent.features.workspace.api.WorkspaceMode

@Composable
internal fun WorkspacePanels(layout: LayoutState, dispatch: (WorkspaceAction) -> Unit,
    content: @Composable (PanelInstance, Boolean) -> Unit) {
    if (layout.panels.isEmpty()) { Text("工作区为空"); return }
    val visible = if (layout.mode == WorkspaceMode.PhoneStack) listOfNotNull(layout.activePanel()) else visiblePanels(layout)
    val columns = if (layout.mode == WorkspaceMode.TabletPanels && visible.size > 1) 2 else 1
    val rows = ((visible.size + columns - 1) / columns).coerceAtLeast(1)
    Layout(content = {
        layout.panels.forEach { panel -> key(panel.id) {
            val shown = panel in visible
            Box((if (shown) Modifier else Modifier.clearAndSetSemantics {})
                .fillMaxSize().testTag("workspace-panel-${panel.key.value}")) {
                content(panel, panel.id == layout.activePanelId)
            }
        } }
    }, modifier = Modifier.fillMaxSize().testTag("workspace-panels")) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val cells = measurables.map { it.measure(androidx.compose.ui.unit.Constraints.fixed(width / columns, height / rows)) }
        layout(width, height) {
            cells.forEachIndexed { index, cell ->
                val slot = visible.indexOf(layout.panels[index])
                if (slot >= 0) cell.placeRelative((slot % columns) * (width / columns), (slot / columns) * (height / rows))
            }
        }
    }
}

private fun visiblePanels(layout: LayoutState): List<PanelInstance> {
    val first = layout.panels.take(4)
    val active = layout.activePanel()
    return if (active == null || active in first) first else first.take(3) + active
}
