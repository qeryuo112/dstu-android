package com.deepstudent.features.workspace.backend.application

import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.core.common.time.RandomSource
import com.deepstudent.core.common.time.SystemWallClock
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.features.workspace.api.CloseDecision
import com.deepstudent.features.workspace.api.CloseResult
import com.deepstudent.features.workspace.api.DraftStore
import com.deepstudent.features.workspace.api.DraftWrite
import com.deepstudent.features.workspace.api.LayoutSnapshotStore
import com.deepstudent.features.workspace.api.OpenPanel
import com.deepstudent.features.workspace.api.PanelDraft
import com.deepstudent.features.workspace.api.PanelId
import com.deepstudent.features.workspace.api.PanelInstance
import com.deepstudent.features.workspace.api.ResourceKey
import com.deepstudent.features.workspace.api.SaveOutcome
import com.deepstudent.features.workspace.api.Workspace
import com.deepstudent.features.workspace.api.WorkspaceMode
import com.deepstudent.features.workspace.api.WorkspaceState
import com.deepstudent.features.workspace.backend.domain.CloseGuard
import com.deepstudent.features.workspace.backend.domain.WorkspaceLayoutMachine
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WorkspaceSession(
    internal val hosts: PanelHostRegistry,
    internal val drafts: DraftStore,
    internal val snapshots: LayoutSnapshotStore,
    internal val clock: WallClock = SystemWallClock,
    internal val random: RandomSource = secureRandomSource(),
    internal val logger: Logger = NoopLogger,
) : Workspace {
    internal val mutableState = MutableStateFlow(WorkspaceState())
    override val state: StateFlow<WorkspaceState> = mutableState.asStateFlow()
    internal val mutex = Mutex()
    internal val machine = WorkspaceLayoutMachine()
    internal val closeGuard = CloseGuard()

    override suspend fun restore() = restoreWorkspace()

    override suspend fun open(command: OpenPanel): PanelInstance = openPanel(command)

    override suspend fun focus(panelId: PanelId) = focusPanel(panelId)

    override suspend fun loadDraft(panelId: PanelId): PanelDraft? =
        if (state.value.layout.panel(panelId) == null) null else loadDraftOrNull(panelId)

    override suspend fun updateDraft(panelId: PanelId, bytes: ByteArray): DraftWrite =
        writeDraft(panelId, bytes)

    override suspend fun clearDraft(panelId: PanelId): Boolean = clearPersistedDraft(panelId)

    override suspend fun updateResource(panelId: PanelId, resource: ResourceKey?) =
        changeResource(panelId, resource)

    override suspend fun save(panelId: PanelId): SaveOutcome = saveWorkspacePanel(panelId)

    override suspend fun acknowledgeSaveUncertain(panelId: PanelId) =
        acknowledgeUncertainSave(panelId)

    override suspend fun close(panelId: PanelId): CloseResult = closeWorkspacePanel(panelId)

    override suspend fun resolveClose(decision: CloseDecision): CloseResult =
        resolveWorkspaceClose(decision)

    override fun cancelClose() {
        mutableState.update { current ->
            if (current.savingPanelId == null) current.copy(pendingClose = null) else current
        }
    }

    override suspend fun setMode(mode: WorkspaceMode) = changeMode(mode)
}

private fun secureRandomSource(): RandomSource {
    val random = SecureRandom()
    return RandomSource { count -> ByteArray(count).also(random::nextBytes) }
}
