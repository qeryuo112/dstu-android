package com.deepstudent.services.sync.backend

/**
 * An already authenticated archive and sidecar must be bound to a durable target operation.
 * Installation and operation IDs are canonical UUIDs, persisted before making a remote claim;
 * neither may be regenerated after a timeout or a process restart. Instance/writer IDs are the
 * exact frozen identifiers, not necessarily UUIDs. Credentials are never part of this request.
 */
data class ContinuityClaimRequest(
    val instanceId: String,
    val writerId: String,
    val targetInstallationId: String,
    val operationId: String,
    val archiveSha256: String,
    val sidecarSha256: String,
) {
    override fun toString(): String = "ContinuityClaimRequest(redacted)"
}

enum class ContinuityClaimFailure {
    INVALID_REQUEST, REMOTE_UNAVAILABLE, CONDITIONAL_CREATE_UNVERIFIED, WRITER_NOT_RETIRED,
    ATTEMPT_NOT_PENDING, ALREADY_CLAIMED, READBACK_MISMATCH, DEPENDENCY_UNAVAILABLE, WRITER_SEQUENCE_CHANGED, WRITER_SEQUENCE_CONFLICT,
}

/** No upstream exception or its message is retained: they may contain credentials. */
class ContinuityClaimException(val reason: ContinuityClaimFailure) : RuntimeException(reason.name)

/** A remote reservation only; this does not install an identity or fence an old writer. */
class ContinuityClaimReceipt internal constructor(val request: ContinuityClaimRequest) {
    override fun toString(): String = "ContinuityClaimReceipt(redacted)"
}

fun interface ContinuityClaimPort {
    suspend fun claim(request: ContinuityClaimRequest): ContinuityClaimReceipt
}

/**
 * A verifier is a trusted security boundary, not a feature flag or a GET-after-PUT probe.
 * It must use independent, authenticated evidence for this exact provider/root and instance that:
 * - conditional create is atomic across all clients, never overwrites, and is durable;
 * - reads are consistent, cancellable and bounded (including the initial RemoteGate reads);
 * - the managed claim namespace cannot be overwritten/deleted by untrusted clients and is
 *   retained across retries and successful handoffs; and
 * - transport redirects or proxies cannot silently discard the precondition.
 * Evidence must remain valid through claim, local commit, and subsequent retries. The existing
 * ObjectProvider interface and the presence of an If-None-Match header alone do NOT supply it.
 * No production verifier is supplied here. Return null when any guarantee is unverified.
 */
fun interface ContinuityClaimConditionalCreateVerifier {
    suspend fun verify(provider: ObjectProvider, instanceId: String): ContinuityClaimConditionalCreateProof?
}

/** Bound to the identical provider object and exact instance; its issuer is the trusted verifier. */
class ContinuityClaimConditionalCreateProof(val provider: ObjectProvider, val instanceId: String) {
    override fun toString(): String = "ContinuityClaimConditionalCreateProof(redacted)"
}

/**
 * Must independently obtain and authenticate proof for this exact repository and old writer,
 * including unchanged-since-snapshot and retirement/revocation, for the exact archive and sidecar
 * hashes bound in the request. The proof must be independently authenticated; the hashes identify
 * the snapshot but do not prove it. A UI checkbox, sidecar value, a locally invented statement or
 * this claim's readback is not proof. The issuer and caller
 * must keep that guarantee valid from the snapshot until the atomic local identity commit, and
 * revalidate it at that commit. Failure to establish/maintain it returns null and fails closed.
 * This port does not itself stop, revoke, or fence a writer; no production authority is supplied.
 */
fun interface ContinuityClaimRetirementVerifier {
    suspend fun verify(request: ContinuityClaimRequest): ContinuityClaimRetirementProof?
}

/** The verifier must authenticate this whole binding, not just echo the request. */
class ContinuityClaimRetirementProof(val request: ContinuityClaimRequest) {
    override fun toString(): String = "ContinuityClaimRetirementProof(redacted)"
}

enum class ContinuityClaimAttemptStatus { PENDING, COMPLETED, MISSING_OR_MISMATCHED }

/**
 * A trusted durable local ledger, not a UI value. PENDING is returned only for the exact complete
 * request previously persisted for this installation and operation, including both archive hashes.
 * COMPLETED or missing/mismatched attempts must never be treated as retryable. The caller must
 * atomically recheck PENDING, apply the identity and transition to COMPLETED in one local database
 * transaction; concurrent callers may obtain the same pending receipt, but only one may commit it.
 * The installation ID must identify this persistent installation and must not be cloned/reused.
 * The ledger and both verifier ports must be bounded and cooperate with coroutine cancellation;
 * the claim deadline cannot interrupt a blocking implementation.
 */
fun interface ContinuityClaimAttemptLedger {
    suspend fun status(request: ContinuityClaimRequest): ContinuityClaimAttemptStatus
}
