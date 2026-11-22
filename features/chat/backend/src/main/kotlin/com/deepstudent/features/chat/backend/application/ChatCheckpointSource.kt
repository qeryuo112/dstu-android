package com.deepstudent.features.chat.backend.application

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.services.runtime.api.BusinessCompletion
import com.deepstudent.services.runtime.api.BusinessCompletionSource

/** This S1 probe writes only runtime checkpoints, not shared chat messages or domain facts. */
object ChatCheckpointSource : BusinessCompletionSource {
    override suspend fun find(taskId: TaskId, generation: Long): BusinessCompletion? = null
    override suspend fun pending(consumerId: String, limit: Int): List<BusinessCompletion> = emptyList()
    override suspend fun acknowledge(consumerId: String, factId: String) = Unit
}
