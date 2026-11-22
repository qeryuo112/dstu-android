package com.deepstudent.platform.security

import java.util.UUID

enum class SecurityOperation {
    KEYSTORE_OPEN,
    KEYSTORE_REBUILD,
    CREDENTIAL_PUT,
    CREDENTIAL_GET,
    CREDENTIAL_DELETE,
    DSBK_ENCRYPT,
    DSBK_DECRYPT,
    DSBK_PREFLIGHT,
    MASTER_KEY_PROBE,
    MASTER_KEY_RESET,
    STAGING_CLEANUP,
}

enum class SecurityPhase {
    HEADER,
    DISPATCH,
    V1_DECRYPT,
    V2_DECRYPT,
    COMMIT,
    KEY_PROBE,
    RESET_RECHECK,
    RESET_APPLY,
}

enum class SecurityOutcome {
    STARTED,
    SUCCEEDED,
    NOT_FOUND,
    REJECTED,
    FAILED,
}

data class SecurityTraceContext(
    val correlationId: String,
    val phase: SecurityPhase,
    val elapsedMs: Long,
    val masterKeyFailure: MasterKeyFailure? = null,
) {
    init {
        require(correlationId.matches(Regex("[0-9a-f]{32}"))) {
            "audit correlation ID must be generated locally"
        }
        require(elapsedMs >= 0) { "audit duration must not be negative" }
    }
}

data class SecurityAuditEvent(
    val operation: SecurityOperation,
    val outcome: SecurityOutcome,
    val resourceDigest: String? = null,
    val bytes: Long? = null,
    val errorCode: String? = null,
    val trace: SecurityTraceContext? = null,
) {
    val owner: String get() = "platform.security"
    val correlationId: String? get() = trace?.correlationId
    val phase: SecurityPhase? get() = trace?.phase
    val elapsedMs: Long? get() = trace?.elapsedMs
    val masterKeyFailure: MasterKeyFailure? get() = trace?.masterKeyFailure

    init {
        require(resourceDigest == null || resourceDigest.matches(Regex("[0-9a-f]{12}"))) {
            "audit digest must be a fixed-width lowercase hash"
        }
        require(bytes == null || bytes >= 0) { "audit byte count must not be negative" }
        require(errorCode == null || CredentialStoreError.entries.any { it.name == errorCode } ||
            DsbkError.entries.any { it.name == errorCode }
        ) { "audit error must be a fixed security code" }
    }

    override fun toString(): String = buildString {
        append("owner=").append(owner)
        append(" operation=").append(operation.name)
        append(" outcome=").append(outcome.name)
        correlationId?.let { append(" correlationId=").append(it) }
        phase?.let { append(" phase=").append(it.name) }
        if (phase != null) append(" state=").append(outcome.name)
        elapsedMs?.let { append(" elapsedMs=").append(it) }
        masterKeyFailure?.let { append(" reason=").append(it.name) }
        resourceDigest?.let { append(" resource=").append(it) }
        bytes?.let { append(" bytes=").append(it) }
        errorCode?.let { append(" error=").append(it) }
    }
}

fun interface SecurityAudit {
    fun onEvent(event: SecurityAuditEvent)

    companion object {
        val NONE: SecurityAudit = SecurityAudit { }
    }
}

internal fun SecurityAudit.record(
    operation: SecurityOperation,
    outcome: SecurityOutcome,
    resourceDigest: String? = null,
    bytes: Long? = null,
    errorCode: String? = null,
) {
    try {
        onEvent(SecurityAuditEvent(operation, outcome, resourceDigest, bytes, errorCode))
    } catch (_: Exception) {
        // Diagnostics must never change a security decision.
    }
}

/** A correlation ID and monotonic duration belong to one public operation. */
internal class SecurityTrace(
    private val audit: SecurityAudit,
    private val operation: SecurityOperation,
) {
    private val id = UUID.randomUUID().toString().replace("-", "")
    private val started = System.nanoTime()
    private var phase = SecurityPhase.DISPATCH

    fun start(next: SecurityPhase) {
        phase = next
        emit(SecurityOutcome.STARTED)
    }

    fun succeed() = emit(SecurityOutcome.SUCCEEDED)

    fun fail(error: DsbkException) = emit(SecurityOutcome.FAILED, error.error.name)

    fun fail(error: CredentialStoreException) =
        emit(SecurityOutcome.FAILED, error.error.name, error.masterKeyFailure)

    private fun emit(outcome: SecurityOutcome, error: String? = null, reason: MasterKeyFailure? = null) {
        try {
            val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0)
            audit.onEvent(SecurityAuditEvent(
                operation = operation,
                outcome = outcome,
                errorCode = error,
                trace = SecurityTraceContext(id, phase, elapsed, reason),
            ))
        } catch (_: Exception) {
            // Diagnostics must never change a security decision.
        }
    }
}
