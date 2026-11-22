package com.deepstudent.services.backup.data

import java.io.File

/** Caller runs before any normal lease, after durable confirmation and queueing. */
sealed interface RestoreExecutionResult {
    data object Busy : RestoreExecutionResult
    data class Committed(val operationId: String, val disposition: CommitDisposition) : RestoreExecutionResult
    data class Failed(val operationId: String, val phase: RestorePhase, val error: RestoreError) : RestoreExecutionResult {
        /** Retry requires an explicit user action; a changed archive requires a new operation id. */
        val retryable: Boolean get() = error in setOf(RestoreError.CANCELLED, RestoreError.IO_FAILURE, RestoreError.INSUFFICIENT_SPACE)
    }
    data class NeedsRepair(val operationId: String, val phase: RestorePhase, val error: RestoreError) : RestoreExecutionResult
}

enum class CommitDisposition { NEWLY_COMMITTED, ALREADY_ACTIVE, PREVIOUSLY_COMMITTED }
enum class RestorePhase { ACQUIRE, PREPARE, PREFLIGHT, SNAPSHOT, EXTRACT, VERIFY_DATABASES, VERIFY_RESOURCES, ACTIVATE, COMPLETE }
enum class RestoreError {
    INVALID_PLAN, UNSAFE_ARCHIVE, UNTRUSTED_TARGET, LIMIT_EXCEEDED, MISSING_ENTRY,
    MANIFEST_CHANGED, SIZE_MISMATCH, HASH_MISMATCH, INSUFFICIENT_SPACE,
    SNAPSHOT_UNSAFE, DATABASE_INVALID, RESOURCE_INVALID, CANCELLED, IO_FAILURE, STORAGE_STATE,
}

enum class RestoreCheckpoint {
    MAINTENANCE_ACQUIRED, STAGING_RESET, ARCHIVE_RECHECKED, SNAPSHOT_ENTRY, SNAPSHOT_COMPLETE,
    ENTRY_WRITTEN, DATABASES_VERIFIED, RESOURCES_VERIFIED, BEFORE_ACTIVATION, ACTIVATION_RETURNED,
}

/** Checkpoints may block for a process-death test. No path or data is exposed. */
fun interface RestoreCheckpointHook {
    fun reached(checkpoint: RestoreCheckpoint)
    companion object { val NONE = RestoreCheckpointHook { } }
}

data class RestoreEvent(
    val operationId: String,
    val phase: RestorePhase,
    val status: RestoreStatus,
    val elapsedMillis: Long,
    val error: RestoreError? = null,
) { val owner: String get() = "services.backup.restore" }

enum class RestoreStatus { STARTED, SUCCEEDED, FAILED }
fun interface RestoreAudit {
    fun record(event: RestoreEvent)
    companion object { val NONE = RestoreAudit { } }
}

/** Both callbacks run within the maintenance lease on the private candidate, never on the old slot. */
interface RestoreCandidateVerifier {
    /** Validate/migrate the four shared SQLite histories and perform SQLite integrity checks. */
    fun verifyDatabases(candidate: File)
    /** Validate VFS blob files, hashes and database references. */
    fun verifyResources(candidate: File)
}

internal class RestoreFailure(val code: RestoreError) : Exception(code.name)
