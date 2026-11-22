package com.deepstudent.platform.storage

import java.io.File

/** Failures deliberately contain neither filesystem names nor business data. */
enum class DataSpaceError {
    BUSY,
    INVALID_OPERATION_ID,
    UNSAFE_ENTRY,
    UNKNOWN_ENTRY,
    CORRUPT_STATE,
    POINTER_MISSING,
    INCOMPLETE_STAGING,
    VALIDATION_FAILED,
    FOREIGN_STAGING,
    CLOSED,
    IO_FAILURE,
}

class DataSpaceException(val error: DataSpaceError) : IllegalStateException(error.name)

enum class DataSpacePhase {
    ACQUIRE, RELEASE, MAINTENANCE, PREPARE, ACTIVATE, DISCARD,
    STAGING_PREPARED, STAGING_VALIDATED, STAGING_DURABLE,
    POINTER_TEMP_DURABLE, POINTER_REPLACED, POINTER_DURABLE, COMMIT_RECORDED,
}

enum class DataSpaceOutcome { STARTED, SUCCEEDED, REJECTED, FAILED }

data class DataSpaceEvent(
    val correlationId: String,
    val phase: DataSpacePhase,
    val outcome: DataSpaceOutcome,
    val elapsedMillis: Long,
    val error: DataSpaceError? = null,
) {
    val owner: String get() = "platform.storage"
}

fun interface DataSpaceAudit {
    fun record(event: DataSpaceEvent)

    companion object { val NONE = DataSpaceAudit { } }
}

/** A hook may block for process-death tests; it must not change the files. */
fun interface DataSpacePhaseHook {
    fun onPhase(phase: DataSpacePhase)

    companion object { val NONE = DataSpacePhaseHook { } }
}

enum class StagingStatus { WRITABLE, ACTIVE, PREVIOUSLY_ACTIVATED }
enum class ActivationResult { ACTIVATED, ALREADY_ACTIVE, PREVIOUSLY_ACTIVATED }

interface ActiveSlotLease : AutoCloseable {
    /** Valid only until this lease closes. Owners must close all files first. */
    val root: File
    override fun close()
}

interface DataSpaceStaging {
    val status: StagingStatus
    /** Only WRITABLE staging exposes its data directory, while its maintenance scope is open. */
    val root: File
}

interface DataSpaceMaintenance {
    /** The currently active root, changing when this scope successfully activates a slot. */
    val active: File
    fun prepare(operationId: String): DataSpaceStaging
    /** [validate] must check the complete domain-specific contents and throw on any failure. */
    fun activate(staging: DataSpaceStaging, validate: (File) -> Unit): ActivationResult
    fun discard(staging: DataSpaceStaging)
}
