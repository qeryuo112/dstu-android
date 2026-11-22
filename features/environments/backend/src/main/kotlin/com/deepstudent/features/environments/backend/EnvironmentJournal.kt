package com.deepstudent.features.environments.backend

import com.deepstudent.features.environments.api.EnvironmentFailure
import com.deepstudent.features.environments.api.PackageReceipt
import java.time.Instant

enum class EnvironmentCheckpoint {
    VERIFY,
    EXTRACT,
    HEALTH,
    ACTIVATE,
    DONE,
}

data class EnvironmentJournalIdentity(
    val rootfsId: String? = null,
    val rootfsSha256: String? = null,
    val generationId: String? = null,
)

data class EnvironmentJournal(
    val identity: EnvironmentJournalIdentity = EnvironmentJournalIdentity(),
    val checkpoint: EnvironmentCheckpoint = EnvironmentCheckpoint.VERIFY,
    val activeGeneration: String? = null,
    val receipts: Map<String, PackageReceipt> = emptyMap(),
    val pendingRecipeId: String? = null,
    val lastTaskId: String? = null,
    val failure: EnvironmentFailure? = null,
    val updatedAt: Instant,
) {
    val rootfsId: String? get() = identity.rootfsId
    val rootfsSha256: String? get() = identity.rootfsSha256
    val generationId: String? get() = identity.generationId
}
