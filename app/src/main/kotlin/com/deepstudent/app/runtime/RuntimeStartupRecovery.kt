package com.deepstudent.app.runtime

import com.deepstudent.app.execution.MainShellTasks
import com.deepstudent.app.runtime.script.MainScriptResources
import com.deepstudent.app.storage.AndroidOperationLogger
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.time.SystemMonotonicClock
import com.deepstudent.core.common.time.SystemWallClock
import com.deepstudent.features.chat.backend.application.ChatCheckpointSource
import com.deepstudent.features.chat.backend.application.RuntimeChatStreams
import com.deepstudent.services.runtime.api.DeadOwnerPredicate
import com.deepstudent.services.runtime.api.RecoveryDecision
import com.deepstudent.services.runtime.api.RecoveryHandler
import com.deepstudent.services.runtime.api.RecoveryPublisher
import com.deepstudent.services.runtime.api.RecoveryRegistration
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.backend.application.recovery.RecoveryActions
import com.deepstudent.services.runtime.backend.application.recovery.RecoveryCoordinator
import com.deepstudent.services.runtime.backend.application.recovery.RecoveryReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class RuntimeStartupRecovery(private val runtime: MainRuntimeResources) {
    private val snapshots = MutableStateFlow<Map<TaskId, RuntimeSnapshot>>(emptyMap())
    val recoveredSnapshots = snapshots.asStateFlow()

    suspend fun recover(): RecoveryReport {
        val handler = RecoveryHandler { RecoveryDecision.Interrupted }
        val registrations = listOf(MainRuntimeResources.NOTE_CREATE, MainRuntimeResources.NOTE_IMPORT).map {
            RecoveryRegistration(it, runtime.completionSource, handler)
        } + listOf(
            RecoveryRegistration(RuntimeChatStreams.KIND, ChatCheckpointSource, handler),
            RecoveryRegistration(MainShellTasks.KIND, MainShellTasks.noBusinessCompletion, handler),
            RecoveryRegistration(MainScriptResources.KIND, runtime.script.completionSource, handler),
            RecoveryRegistration(MainRuntimeResources.SCHEDULED_NOTE_CREATE, runtime.completionSource) { snapshot ->
                if (snapshot.execution.cancelRequested) RecoveryDecision.Interrupted else RecoveryDecision.KeepPaused
            },
        )
        val actions = RecoveryActions(
            runtime.ledger,
            runtime.supervisor,
            SystemWallClock,
            runtime.ownerId,
            MainRuntimeResources.LEASE_DURATION,
            DeadOwnerPredicate {
                it.ownership.ownerId?.startsWith("main:") == true &&
                    it.ownership.ownerId != runtime.ownerId
            },
        )
        return RecoveryCoordinator(
            actions,
            registrations,
            RecoveryPublisher { snapshots.value = snapshots.value + (it.taskId to it) },
            AndroidOperationLogger,
            SystemMonotonicClock,
        ).recover()
    }
}
