package com.deepstudent.services.sync.backend

import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.services.sync.api.SyncError
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal val CLAIM_NAMESPACE = "dstu-management/continuity-claims/v1/"
internal const val TEST_INSTANCE = "default"
internal const val TEST_WRITER = "device-7f3a91c2"

internal fun claimRequest(
    instance: String = TEST_INSTANCE,
    writer: String = TEST_WRITER,
    target: String = UUID.randomUUID().toString(),
    operation: String = UUID.randomUUID().toString(),
    archive: String = "a".repeat(64),
    sidecar: String = "b".repeat(64),
) = ContinuityClaimRequest(instance, writer, target, operation, archive, sidecar)

internal class ClaimMemory(instance: String = TEST_INSTANCE) : ObjectProvider {
    val objects = ConcurrentHashMap<String, ByteArray>()
    val putCount = AtomicInteger()
    val deleteCount = AtomicInteger()

    init {
        objects[PairingService.FORMAT_KEY] = PairingService.FORMAT.toByteArray()
        objects[PairingService.INSTANCE_KEY] = """{"format_version":4,"instance_id":"$instance"}""".toByteArray()
    }

    override suspend fun get(key: String): ByteArray? = objects[key]?.copyOf()
    override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) {
        check(ifAbsent) { "claim must always use conditional create" }
        putCount.incrementAndGet()
        if (objects.putIfAbsent(key, value.copyOf()) != null) throw SyncFailure(SyncError.ALREADY_EXISTS)
    }
    override suspend fun list(prefix: String): List<String> = objects.keys.filter { it.startsWith(prefix) }
    override suspend fun delete(key: String) { deleteCount.incrementAndGet(); objects.remove(key) }
    fun claims(): Map<String, ByteArray> = objects.filterKeys { it.startsWith(CLAIM_NAMESPACE) }
}

internal class ClaimLedger : ContinuityClaimAttemptLedger {
    private val attempts = ConcurrentHashMap<ContinuityClaimRequest, ContinuityClaimAttemptStatus>()
    fun set(request: ContinuityClaimRequest, status: ContinuityClaimAttemptStatus = ContinuityClaimAttemptStatus.PENDING) {
        attempts[request] = status
    }
    override suspend fun status(request: ContinuityClaimRequest): ContinuityClaimAttemptStatus =
        attempts[request] ?: ContinuityClaimAttemptStatus.MISSING_OR_MISMATCHED
}

internal val claimConditionalProof = ContinuityClaimConditionalCreateVerifier { provider, instance ->
    ContinuityClaimConditionalCreateProof(provider, instance)
}
internal val claimRetirementProof = ContinuityClaimRetirementVerifier { request ->
    ContinuityClaimRetirementProof(request)
}

internal fun claimService(
    provider: ObjectProvider,
    ledger: ContinuityClaimAttemptLedger,
    conditional: ContinuityClaimConditionalCreateVerifier = claimConditionalProof,
    retirement: ContinuityClaimRetirementVerifier = claimRetirementProof,
    logger: Logger = object : Logger { override fun log(record: LogRecord) = Unit },
    timeout: Long = 30_000,
) = ContinuityRemoteClaim(provider, conditional, retirement, ledger, logger, timeout)
