package com.deepstudent.app.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.deepstudent.app.editor.EditorTestHost
import com.deepstudent.design.DstuMode
import com.deepstudent.design.DstuTheme
import com.deepstudent.features.workspace.api.CloseDecision
import com.deepstudent.features.workspace.api.LayoutState
import com.deepstudent.features.workspace.api.PanelId
import com.deepstudent.features.workspace.api.PanelInstance
import com.deepstudent.features.workspace.api.PendingClose
import com.deepstudent.features.workspace.api.WorkspaceMode
import com.deepstudent.features.workspace.api.WorkspacePanelKeys
import com.deepstudent.features.workspace.api.WorkspaceState
import com.deepstudent.features.workspace.api.WorkspaceStatus
import com.deepstudent.features.workspace.ui.PanelLauncher
import com.deepstudent.features.workspace.ui.WorkspaceAction
import com.deepstudent.features.workspace.ui.WorkspaceScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class WorkspaceFakeUiDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val host = EditorTestHost()
    private val panels = listOf(WorkspacePanelKeys.Notes, WorkspacePanelKeys.Pdf,
        WorkspacePanelKeys.Chat, WorkspacePanelKeys.Browser).mapIndexed { index, key ->
        PanelInstance(PanelId.parse("panel-$index"), key, null, key.value, dirty = index == 0)
    }
    @Before fun start() = host.start()
    @After fun close() = host.close()

    @Test fun phoneStackShowsOnlyActiveAndCancelDoesNotRemoveDirtyPanel() {
        var state by mutableStateOf(WorkspaceState(WorkspaceStatus.Ready,
            LayoutState(WorkspaceMode.PhoneStack, panels, panels.first().id)))
        val actions = mutableListOf<WorkspaceAction>()
        host.setContent { DstuTheme { Surface {
            Box(Modifier.width(390.dp).fillMaxSize()) {
                WorkspaceScreen(state, panels.map { PanelLauncher(it.key, it.title) }, { action ->
                    actions += action
                    when (action) {
                        is WorkspaceAction.Close -> state = state.copy(pendingClose = PendingClose(action.id, panels.first().key, true))
                        is WorkspaceAction.Decide -> state = state.copy(pendingClose = null)
                        else -> Unit
                    }
                }) { panel, _ -> Text(panel.key.value, Modifier.testTag("fake-${panel.key.value}")) }
            }
        } } }
        compose.onNodeWithTag("fake-notes").assertIsDisplayed()
        compose.onNodeWithTag("fake-chat").assertDoesNotExist()
        compose.onNodeWithTag("workspace-close-notes").performClick()
        compose.onNodeWithTag("workspace-close-cancel").performClick()
        compose.runOnIdle {
            assertEquals(WorkspaceAction.Decide(CloseDecision.Cancel), actions.last())
            assertEquals(4, state.layout.panels.size)
        }
        compose.onNodeWithTag("fake-notes").assertIsDisplayed()
    }

    @Test fun tabletDarkShowsFourIndependentSlotsAndRoutesCommands() {
        val actions = mutableListOf<WorkspaceAction>()
        val state = WorkspaceState(WorkspaceStatus.Ready, LayoutState(WorkspaceMode.TabletPanels, panels, panels.first().id))
        host.setContent { DstuTheme(DstuMode.Dark) { Surface {
            WorkspaceScreen(state, panels.map { PanelLauncher(it.key, it.title) }, actions::add) { panel, _ ->
                Text(panel.key.value, Modifier.testTag("fake-${panel.key.value}"))
            }
        } } }
        panels.forEach { compose.onNodeWithTag("fake-${it.key.value}").assertIsDisplayed() }
        compose.onNodeWithTag("workspace-tab-chat").performClick()
        compose.runOnIdle { assertEquals(WorkspaceAction.Focus(panels[2].id), actions.last()) }
    }
}
