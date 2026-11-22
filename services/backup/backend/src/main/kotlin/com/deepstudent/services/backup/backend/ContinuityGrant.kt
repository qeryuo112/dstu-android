package com.deepstudent.services.backup.backend

import java.time.Instant
import java.util.Collections

/** The exact archive, sidecar and durable target covered by the manager's signature. */
data class ContinuityGrantBinding(
    val archiveSha256: String, val sidecarSha256: String, val targetInstallationId: String, val operationId: String,
) {
    override fun toString() = "ContinuityGrantBinding(redacted)"
}

data class ContinuityGrantValidity(val issuedAtEpochSecond: Long, val expiresAtEpochSecond: Long)

/**
 * UNTRUSTED signing DTO. Constructing this is not verification or permission to take over.
 * [peerCanonicalIds] is the administrator's complete repository device inventory, including the
 * retired writer and devices absent from the sidecar; it is NOT reconstructed from the sidecar.
 * See [ContinuityGrantCodec] for the exact interoperable signing format.
 */
data class ContinuityGrantPayload(
    val repositoryUrl: String,
    val repository: ContinuityRepository,
    val sourceInstallation: ContinuitySourceInstallation,
    val writerId: String,
    val uploadSeq: Long,
    val binding: ContinuityGrantBinding,
    val validity: ContinuityGrantValidity,
    val peerCanonicalIds: List<String>,
) {
    val archiveSha256 get() = binding.archiveSha256
    val sidecarSha256 get() = binding.sidecarSha256
    val targetInstallationId get() = binding.targetInstallationId
    val operationId get() = binding.operationId
    val issuedAtEpochSecond get() = validity.issuedAtEpochSecond
    val expiresAtEpochSecond get() = validity.expiresAtEpochSecond
    override fun toString() = "ContinuityGrantPayload(redacted)"
}

/** Detached untrusted bytes; the constructor copies them and never derives a key from either. */
class ContinuityGrantSignedBytes(payload: ByteArray, signature: ByteArray) {
    private val payload: ByteArray
    private val signature: ByteArray
    init {
        grantRequire(payload.size <= GRANT_MAX_BYTES, ContinuityGrantFailure.TOO_LARGE)
        grantRequire(signature.size <= 512, ContinuityGrantFailure.INVALID_SIGNATURE)
        this.payload = payload.copyOf()
        this.signature = signature.copyOf()
    }
    internal fun payloadCopy() = payload.copyOf()
    internal fun signatureCopy() = signature.copyOf()
    override fun toString() = "ContinuityGrantSignedBytes(redacted)"
}

/**
 * Independent approved configuration, not values supplied by a grant, sidecar or untrusted UI.
 * [administratorKeySpki] is a DER X.509 SubjectPublicKeyInfo pin, NOT an embedded certificate.
 * [repositoryUrl] must come from the paired WebDAV endpoint + root using WebDavPaths and no
 * credentials, not by interpreting the legacy sidecar's separate endpointHint as a URL.
 * IDs must be read from durable app state; operationId is persisted before requesting a grant.
 */
class ContinuityGrantExpectation(
    administratorKeySpki: ByteArray,
    val repositoryUrl: String,
    val targetInstallationId: String,
    val operationId: String,
) {
    private val pin: ByteArray
    init {
        grantRequire(administratorKeySpki.size in 1..8192, ContinuityGrantFailure.INVALID_KEY)
        pin = administratorKeySpki.copyOf()
    }
    internal fun publicKeyBytes() = pin.copyOf()
    override fun toString() = "ContinuityGrantExpectation(redacted)"
}

enum class ContinuityGrantFailure {
    TOO_LARGE, INVALID_FORMAT, INVALID_KEY, INVALID_SIGNATURE, INVALID_EXPECTATION,
    BINDING_MISMATCH, NOT_YET_VALID, EXPIRED, INVALID_INVENTORY, ARCHIVE_IO,
}

/** No original bytes, URL, cryptographic material or underlying exception is retained. */
class ContinuityGrantException(val reason: ContinuityGrantFailure) : Exception(reason.name)

/**
 * Authenticated assertions for exactly one repository, target and durable operation, not a claim,
 * fencing mechanism, activation receipt or independent proof of a WebDAV server's behavior.
 * Only the verifier can construct this. Do not persist this object as proof; persist the signed
 * bytes and reverify with the current trusted clock before claim and at cold-start activation.
 */
class VerifiedContinuityGrant internal constructor(
    payload: ContinuityGrantPayload,
    val verifiedAt: Instant,
    private val observedArchiveBytes: Long,
) {
    val repositoryUrl: String = payload.repositoryUrl
    val repository: ContinuityRepository = payload.repository
    val sourceInstallation: ContinuitySourceInstallation = payload.sourceInstallation
    val writerId: String = payload.writerId
    val uploadSeq: Long = payload.uploadSeq
    val archiveSha256: String = payload.archiveSha256
    val sidecarSha256: String = payload.sidecarSha256
    val targetInstallationId: String = payload.targetInstallationId
    val operationId: String = payload.operationId
    val issuedAtEpochSecond: Long = payload.issuedAtEpochSecond
    val expiresAtEpochSecond: Long = payload.expiresAtEpochSecond
    val peerCanonicalIds: List<String> = Collections.unmodifiableList(ArrayList(payload.peerCanonicalIds))

    /**
     * [archive] must be the preflight's evidence for the SAME read-only archive actually read by
     * the verifier. Its manifest must come from that archive, not a sidecar or user input. The
     * caller must maintain archive immutability through activation and recheck on cold start.
     */
    fun trust(archive: ContinuityArchiveEvidence, now: Instant): ContinuityTrust {
        grantCheckTime(issuedAtEpochSecond, expiresAtEpochSecond, now)
        grantRequire(archive.sha256 == archiveSha256 && archive.bytes == observedArchiveBytes, ContinuityGrantFailure.BINDING_MISMATCH)
        return ContinuityTrust(archive, sourceInstallation, repository,
            ContinuityWriterConfirmation(writerId, stopped = true, unchangedSinceSnapshot = true), sidecarSha256)
    }

    /**
     * Compare ALL independently signed identity fields with the validated sidecar and construct
     * only the aliases actually needed by its cursors from the independently signed full inventory.
     * The caller still must perform the exclusive remote claim and local transaction atomically as
     * prescribed by the identity port. Retired-writer access and inventory completeness must remain
     * true through that transaction; a signature cannot enforce them.
     */
    fun bind(matched: ContinuityValidation.Matched, now: Instant): Map<String, String> {
        grantCheckTime(issuedAtEpochSecond, expiresAtEpochSecond, now)
        val state = matched.state
        grantRequire(matched.archiveSha256 == archiveSha256 && matched.sidecarSha256 == sidecarSha256 &&
            matched.sourceInstallation == sourceInstallation && state.repository == repository &&
            state.upload.deviceId == writerId && state.upload.lastSeq == uploadSeq &&
            matched.takeoverKey == "continuity-takeover/${matched.snapshotEpoch}/$archiveSha256",
            ContinuityGrantFailure.BINDING_MISMATCH)
        val inventory = grantInventory(peerCanonicalIds, writerId)
        val aliases = linkedMapOf<String, String>()
        for (cursor in state.cursors) {
            aliases[cursor.uploaderDeviceId] = inventory[cursor.uploaderDeviceId]
                ?: throw ContinuityGrantException(ContinuityGrantFailure.INVALID_INVENTORY)
        }
        return Collections.unmodifiableMap(aliases)
    }

    override fun toString() = "VerifiedContinuityGrant(redacted)"
}

internal fun grantRequire(condition: Boolean, reason: ContinuityGrantFailure = ContinuityGrantFailure.INVALID_FORMAT) {
    if (!condition) throw ContinuityGrantException(reason)
}
