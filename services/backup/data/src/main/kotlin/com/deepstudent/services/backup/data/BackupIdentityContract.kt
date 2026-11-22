package com.deepstudent.services.backup.data

import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.services.backup.backend.ContinuityValidation
import java.util.Collections

/** Non-secret values binding an authenticated remote claim to the exact validated snapshot. */
class BackupIdentityClaimBinding internal constructor(matched: ContinuityValidation.Matched) {
    val takeoverKey: String = matched.takeoverKey
    val snapshotEpoch: String = matched.snapshotEpoch
    val archiveSha256: String = matched.archiveSha256
    val sidecarSha256: String = matched.sidecarSha256
    val sourceInstallationId: String = matched.sourceInstallation.installationId
    val sourceApplicationId: String = matched.sourceInstallation.applicationId
    val sourceVersionName: String = matched.sourceInstallation.versionName
    val sourceVersionCode: Long = matched.sourceInstallation.versionCode
    val instanceId: String = matched.state.repository.instanceId
    val provider: String = matched.state.repository.provider
    val endpointHint: String = matched.state.repository.endpointHint
    val writerId: String = matched.state.upload.deviceId
    val lastUploadedSequence: Long = matched.state.upload.lastSeq
    val uploaderIds: List<String> = Collections.unmodifiableList(matched.state.cursors.map { it.uploaderDeviceId })
    override fun toString(): String = "BackupIdentityClaimBinding(redacted)"
}

/** Constructed only by the trusted claim bridge after authenticating remote device manifests. */
class BackupIdentityPeerAliases internal constructor(aliases: Map<String, String>) {
    internal val canonicalBySource: Map<String, String> = Collections.unmodifiableMap(HashMap(aliases))
    override fun toString(): String = "BackupIdentityPeerAliases(redacted)"
}

/**
 * Trust boundary, not a user confirmation or an ordinary boolean verifier.
 *
 * The composition root must supply an independently authenticated remote implementation. It must
 * validate EVERY binding field against independent authenticated evidence, authenticate the target
 * installation, prove the old writer has not advanced since this authenticated snapshot, establish
 * a durable globally exclusive claim for this snapshot/writer, and fence the old writer (including
 * after this callback) before calling [commit]. The authoritative remote sequence/high-water mark
 * must agree with the snapshot; a user assertion or self-hashed sidecar is not evidence. It must
 * keep that guarantee
 * throughout the complete synchronous callback, including the SQLite commit. It must independently
 * resolve each source uploader ID to its canonical device ID from a complete, authenticated remote
 * manifest inventory. It must reject ambiguous raw/short-hash aliases across ALL repository devices,
 * including devices absent from the sidecar. The callback evidence must contain exactly the source
 * mappings, not UI-provided guesses. A UI, sidecar-derived
 * self-assertion, local mutex, expiry-prone lease, or per-target receipt cannot satisfy this contract.
 * Missing, ambiguous, expired or unavailable proof MUST throw before [commit]. No network or user
 * interaction is allowed from within [commit]. The implementation must call it exactly once, on the
 * calling thread, and not retain it. If it throws after a successful callback, the caller must treat
 * the outcome as uncertain and reconcile the local receipt and authoritative remote claim.
 * [completePendingInTransaction] must synchronously check and consume the exact durable PENDING
 * attempt on this same candidate database; it may perform no network or user interaction. Throwing
 * from it rolls the identity changes and attempt transition back together.
 *
 * No production implementation is supplied here. The Kotlin constructor is module-internal so a
 * UI/caller cannot instantiate an ad-hoc adapter; the composition root must install an audited bridge
 * implemented within this module after its remote verifier is available. Implementations remain
 * security-critical trusted code: Kotlin visibility is not cryptography, and Java callers, JVM
 * reflection or malicious code inside this module can bypass it. Test fakes must never ship in product.
 */
abstract class BackupIdentityClaimPort internal constructor() {
    abstract fun withVerifiedExclusiveClaim(binding: BackupIdentityClaimBinding, commit: (BackupIdentityPeerAliases) -> Unit)

    /** Override only in the audited bridge. The default cannot silently omit the attempt ledger. */
    open fun completePendingInTransaction(binding: BackupIdentityClaimBinding, candidate: SqlOperations) {
        throw BackupIdentityException(BackupIdentityFailure.CLAIM_UNAVAILABLE)
    }

    companion object {
        val UNAVAILABLE: BackupIdentityClaimPort = object : BackupIdentityClaimPort() {
            override fun withVerifiedExclusiveClaim(binding: BackupIdentityClaimBinding, commit: (BackupIdentityPeerAliases) -> Unit) {
                throw BackupIdentityException(BackupIdentityFailure.CLAIM_UNAVAILABLE)
            }
        }
    }
}

enum class BackupIdentityFailure {
    CLAIM_UNAVAILABLE, CLAIM_PROTOCOL, CLAIM_COMPLETION_UNKNOWN, NOT_VFS, CONTRACT_MISMATCH, DUPLICATE_TAKEOVER,
    BINDING_CONFLICT, PENDING_PUBLICATION, PUBLICATION_CONFLICT, INVALID_SEQUENCE, CURSOR_ALIAS_CONFLICT,
    AUTHORIZATION_MISMATCH, COMMIT_MISMATCH, APPLIED_STATE_MISMATCH,
}

/** Fixed diagnostic only: no SQL, paths, repository hints, device IDs or payloads. */
class BackupIdentityException(val diagnostic: BackupIdentityFailure) :
    IllegalStateException("backup identity: ${diagnostic.name}")

/** The caller must obtain a fresh or independently authenticated external writer ID and pair again. */
data object BackupIdentityRePairRequired

enum class BackupIdentityMode { ORDINARY, CONTINUITY }
enum class BackupIdentityStatus { STARTED, COMMITTED, VERIFIED, FAILED }

data class BackupIdentityLogEvent(
    val correlationId: String,
    val mode: BackupIdentityMode,
    val status: BackupIdentityStatus,
    val elapsedMillis: Long,
    val diagnostic: BackupIdentityFailure? = null,
) {
    val owner: String get() = "services/backup/data"
    val phase: String get() = "identity-restore"
}

fun interface BackupIdentityLog {
    fun onEvent(event: BackupIdentityLogEvent)
    companion object { val NONE = BackupIdentityLog { } }
}
