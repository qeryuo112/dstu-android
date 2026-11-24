package com.deepstudent.services.runtime.api

import com.deepstudent.core.common.id.TaskId
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** Time and total retained output for one invocation, not a reusable grant. */
data class CapabilityBudget(val durationMillis: Long, val outputBytes: Int) {
    init {
        require(durationMillis in 1..300_000) { "invalid capability duration" }
        require(outputBytes in 0..1_048_576) { "invalid capability output limit" }
    }
}

/** Only public IDs and a digest of frozen input belong in the durable ledger. */
data class CapabilityBinding(
    val caller: RuntimeRecordKey,
    val target: RuntimeRecordKey,
    val uid: Int,
    val resource: RuntimeRecordKey,
    val fingerprint: String,
    val budget: CapabilityBudget,
) {
    init {
        require(uid >= 0) { "invalid executor UID" }
        require(fingerprint.length == 64 && fingerprint.all { it in "0123456789abcdef" }) { "invalid capability digest" }
    }
}

data class CapabilityAddress(val task: RuntimeTaskGeneration, val id: RuntimeRecordKey)

data class CapabilityTicket(
    val address: CapabilityAddress,
    val operation: RuntimeRecordKey,
    val binding: CapabilityBinding,
    val requestedAt: Instant,
    val reviewText: String? = null,
)

enum class CapabilityBlock { DENIED, EXPIRED, STALE, EXECUTION_TIMEOUT }

sealed interface CapabilityOutcome<out T> {
    data class Performed<T>(val result: T) : CapabilityOutcome<T>
    data class Blocked(val reason: CapabilityBlock) : CapabilityOutcome<Nothing>
}

/** UI port: never receives a callable grant or the executor. */
interface CapabilityApprovals {
    fun pending(): Flow<List<CapabilityTicket>>
    suspend fun decide(address: CapabilityAddress, allow: Boolean): Boolean
    suspend fun revoke(task: RuntimeTaskGeneration): Boolean
}

/** Equality on both axes also invalidates a ticket if the global epoch changes below the task epoch. */
data class CapabilityEpoch(val global: Long, val task: Long)

interface CapabilityEpochStore {
    fun current(task: TaskId): CapabilityEpoch
    fun revokeTask(task: TaskId, now: Instant): Long
}

fun interface CapabilityTicketReader {
    suspend fun pendingCapabilities(): List<CapabilityTicket>
}
