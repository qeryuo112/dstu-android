package com.deepstudent.services.sync.backend

import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.transfer.RemoteGate
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

private const val CLAIM_PREFIX = "dstu-management/continuity-claims/v1/"
private const val ID_LIMIT = 256
private const val MAX_TIMEOUT_MS = 120_000L
private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

/**
 * Only an append-only management reservation, independent of the frozen sync wire format.
 * The reservation is never removed, even on cancellation, timeout or a failed recheck. Retrying
 * an uncertain write is safe only for the same durable PENDING request; all other claims fail.
 * This code cannot stop a non-cooperating old client or administrators who can rewrite the store.
 */
class ContinuityRemoteClaim(
    private val provider: ObjectProvider,
    private val conditionalCreate: ContinuityClaimConditionalCreateVerifier,
    private val retirement: ContinuityClaimRetirementVerifier,
    private val ledger: ContinuityClaimAttemptLedger,
    private val logger: Logger = NoopLogger,
    private val timeoutMillis: Long = 30_000,
    // Persist the exact pending operation before the first conditional PUT can be sent.
    // A crash or timeout after this callback can never be treated as a proven no-write.
    private val beforeConditionalCreate: (ContinuityClaimRequest) -> Unit = {},
) : ContinuityClaimPort {
    init { require(timeoutMillis in 1..MAX_TIMEOUT_MS) { "Invalid claim timeout" } }

    override suspend fun claim(request: ContinuityClaimRequest): ContinuityClaimReceipt {
        val started = System.nanoTime()
        val correlation = claimCorrelation(request.operationId)
        var phase = "validate"
        fun trace(state: String, status: String) = logger.log(LogRecord(
            LogLevel.INFO, "sync.continuity_claim", correlationId = correlation,
            fields = JsonValue.Object.Builder().put("owner", "sync.continuity_claim")
                .put("phase", phase).put("state", state).put("status", status)
                .put("elapsedMs", (System.nanoTime() - started) / 1_000_000).build(),
        ))
        trace("started", "none")
        try {
            val receipt = withTimeout(timeoutMillis) {
                currentCoroutineContext().ensureActive()
                validate(request)
                phase = "repository"
                RemoteGate(provider, request.instanceId).minimumReader()
                phase = "conditional_create_proof"
                requireConditionalProof(request)
                phase = "retirement_proof"
                requireRetirement(request)
                phase = "pending_attempt"
                requirePending(request)
                phase = "claim_intent"
                beforeConditionalCreate(request)
                phase = "conditional_create"
                val record = record(request)
                val created = create(record)
                phase = "readback"
                val readback = provider.get(record.key)
                if (readback == null || !readback.contentEquals(record.bytes)) {
                    fail(if (created) ContinuityClaimFailure.READBACK_MISMATCH else ContinuityClaimFailure.ALREADY_CLAIMED)
                }
                phase = "revalidate"
                currentCoroutineContext().ensureActive()
                RemoteGate(provider, request.instanceId).minimumReader()
                requireConditionalProof(request)
                requireRetirement(request)
                requirePending(request)
                currentCoroutineContext().ensureActive()
                ContinuityClaimReceipt(request)
            }
            trace("succeeded", "none")
            return receipt
        } catch (cancelled: CancellationException) {
            trace("cancelled", "CANCELLED")
            throw cancelled
        } catch (failure: ContinuityClaimException) {
            trace("failed", failure.reason.name)
            throw failure
        } catch (_: SyncFailure) {
            trace("failed", ContinuityClaimFailure.REMOTE_UNAVAILABLE.name)
            fail(ContinuityClaimFailure.REMOTE_UNAVAILABLE)
        } catch (_: Exception) {
            trace("failed", ContinuityClaimFailure.DEPENDENCY_UNAVAILABLE.name)
            fail(ContinuityClaimFailure.DEPENDENCY_UNAVAILABLE)
        }
    }

    private suspend fun requireConditionalProof(request: ContinuityClaimRequest) {
        val proof = conditionalCreate.verify(provider, request.instanceId)
        if (proof == null || proof.provider !== provider || proof.instanceId != request.instanceId) {
            fail(ContinuityClaimFailure.CONDITIONAL_CREATE_UNVERIFIED)
        }
    }

    private suspend fun requireRetirement(request: ContinuityClaimRequest) {
        val proof = retirement.verify(request)
        if (proof == null || proof.request != request) {
            fail(ContinuityClaimFailure.WRITER_NOT_RETIRED)
        }
    }

    private suspend fun requirePending(request: ContinuityClaimRequest) {
        if (ledger.status(request) != ContinuityClaimAttemptStatus.PENDING) fail(ContinuityClaimFailure.ATTEMPT_NOT_PENDING)
    }

    private suspend fun create(record: ClaimRecord): Boolean = try {
        currentCoroutineContext().ensureActive()
        provider.put(record.key, record.bytes.copyOf(), ifAbsent = true)
        true
    } catch (failure: SyncFailure) {
        if (failure.error != SyncError.ALREADY_EXISTS) throw failure
        false
    }
}

private data class ClaimRecord(val key: String, val bytes: ByteArray)

private fun validate(request: ContinuityClaimRequest) {
    identityBytes(request.instanceId)
    identityBytes(request.writerId)
    if (!canonicalUuid(request.targetInstallationId) || !canonicalUuid(request.operationId) ||
        !SHA256_PATTERN.matches(request.archiveSha256) || !SHA256_PATTERN.matches(request.sidecarSha256)) {
        fail(ContinuityClaimFailure.INVALID_REQUEST)
    }
}

private fun identityBytes(value: String): ByteArray {
    if (value.length > ID_LIMIT || value.isBlank() || value != value.trim() || value.any(Char::isISOControl)) {
        fail(ContinuityClaimFailure.INVALID_REQUEST)
    }
    val bytes = try {
        val encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        ByteArray(encoded.remaining()).also(encoded::get)
    } catch (_: CharacterCodingException) {
        fail(ContinuityClaimFailure.INVALID_REQUEST)
    }
    if (bytes.size > ID_LIMIT) fail(ContinuityClaimFailure.INVALID_REQUEST)
    return bytes
}

private fun canonicalUuid(value: String): Boolean {
    if (value.length != 36) return false
    val uuid = try { UUID.fromString(value) } catch (_: IllegalArgumentException) { return false }
    return uuid.toString() == value && uuid.variant() == 2 && uuid.version() in 1..8
}

private fun record(request: ContinuityClaimRequest): ClaimRecord {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("dstu-continuity-claim-v1\u0000".toByteArray(StandardCharsets.UTF_8))
    for (part in listOf(identityBytes(request.instanceId), identityBytes(request.writerId))) {
        digest.update(byteArrayOf((part.size ushr 24).toByte(), (part.size ushr 16).toByte(),
            (part.size ushr 8).toByte(), part.size.toByte()))
        digest.update(part)
    }
    val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val text = "dstu-continuity-claim-v1\n$hash\n${request.targetInstallationId}\n${request.operationId}\n" +
        "${request.archiveSha256}\n${request.sidecarSha256}\n"
    return ClaimRecord("$CLAIM_PREFIX$hash.claim", text.toByteArray(StandardCharsets.US_ASCII))
}

private fun fail(reason: ContinuityClaimFailure): Nothing = throw ContinuityClaimException(reason)

private val CLAIM_OPERATION = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

private fun claimCorrelation(operationId: String): CorrelationId = CorrelationId.parse(
    if (CLAIM_OPERATION.matches(operationId)) operationId else UUID.randomUUID().toString(),
)
