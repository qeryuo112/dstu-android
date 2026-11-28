package com.deepstudent.app.runtime

import com.deepstudent.app.BuildConfig
import com.deepstudent.app.storage.AndroidOperationLogger
import com.deepstudent.app.storage.MainStorageResources
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.features.chat.api.ChatTurnContract
import com.deepstudent.features.chat.backend.application.ChatTurnPipeline
import com.deepstudent.features.chat.backend.application.ChatTurnRecoveryHandler
import com.deepstudent.features.chat.data.NetworkChatStream
import com.deepstudent.features.chat.data.SqlChatTurnStore
import com.deepstudent.features.chat.data.SqlChatTurnUsageReconciler
import com.deepstudent.services.runtime.api.BusinessCompletion
import com.deepstudent.services.runtime.api.BusinessCompletionSource
import com.deepstudent.services.runtime.api.RecoveryHandler
import com.deepstudent.services.runtime.api.RuntimeTaskHandler

internal class MainChatRuntime private constructor(
    private val store: SqlChatTurnStore,
    val handler: RuntimeTaskHandler,
    val completion: BusinessCompletionSource,
    val recovery: RecoveryHandler,
) {
    suspend fun persistedText(taskId: TaskId, generation: Long): String? =
        store.loadInFlight(taskId, generation, ChatTurnContract.sessionId(taskId))?.text

    companion object {
        fun create(storage: MainStorageResources, providers: MainProviderRuntime): MainChatRuntime {
            val store = storage.chatTurns
            val usage = SqlChatTurnUsageReconciler(storage::chatDatabase, storage::chatUsageDatabase, logger = AndroidOperationLogger)
            val transport = MainChatStreamingTransport(
                NetworkChatStream(storage.network, allowLoopback = BuildConfig.DEBUG),
                providers.config,
                providers.access.credentials,
                providers.access.adapters,
            )
            return MainChatRuntime(
                store,
                ChatTurnPipeline(transport, store, logger = AndroidOperationLogger),
                UsageReconciledChatCompletion(store, usage),
                ChatTurnRecoveryHandler(store),
            )
        }
    }
}

/** Runtime acknowledges a terminal fact after any reported usage is safely projected. */
private class UsageReconciledChatCompletion(private val facts: BusinessCompletionSource, private val usage: SqlChatTurnUsageReconciler) :
    BusinessCompletionSource {
    override suspend fun find(taskId: TaskId, generation: Long): BusinessCompletion? =
        facts.find(taskId, generation)?.also { usage.reconcile(taskId, generation) }

    override suspend fun pending(consumerId: String, limit: Int): List<BusinessCompletion> =
        facts.pending(consumerId, limit).onEach { usage.reconcile(it.taskId, it.generation) }

    override suspend fun acknowledge(consumerId: String, factId: String) = facts.acknowledge(consumerId, factId)
}
