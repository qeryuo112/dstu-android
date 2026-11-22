package com.deepstudent.services.backup.data

import com.deepstudent.services.backup.backend.ContinuityValidation
import java.security.SecureRandom
import java.util.Collections
import java.util.UUID

/** The exact immutable request that was independently authorized and remotely claimed. */
data class BackupIdentityAttempt(
    val operationId: String,
    val targetInstallationId: String,
    val instanceId: String,
    val writerId: String,
    val archiveSha256: String,
    val sidecarSha256: String,
) {
    override fun toString(): String = "BackupIdentityAttempt(redacted)"
}

/**
 * Trusted, independently persisted expectation for a single candidate slot. The witness must be
 * freshly random after the archive is frozen and persisted outside the restored archive before staging.
 * Never restore it from the archive, reuse it on another attempt, or log it. This is a provenance
 * check, not the remote claim or a substitute for authenticating repository authorization.
 */
data class BackupIdentityContinuityCommit(
    val attempt: BackupIdentityAttempt,
    val takeoverKey: String,
    val witness: String,
    val aliasesSha256: String,
) {
    override fun toString(): String = "BackupIdentityContinuityCommit(redacted)"
}

/** This typed token records the trusted caller's assertion; this module does not authenticate it. */
class BackupIdentityAuthorization internal constructor(
    internal val snapshot: BackupIdentityAuthorizedSnapshot,
    internal val aliases: BackupIdentityPeerAliases,
    val expected: BackupIdentityContinuityCommit,
) {
    override fun toString(): String = "BackupIdentityAuthorization(redacted)"
}

/**
 * Security-critical App composition boundary. Calling this factory asserts that the caller has:
 *
 * - authenticated an independently signed repository-management authorization for the exact
 *   attempt, including permanent old-writer retirement, an unchanged snapshot and continuing
 *   exclusive/atomic remote-claim capability;
 * - actually completed ContinuityRemoteClaim for that same tuple and authenticated the complete
 *   peer alias inventory (including ambiguous aliases absent from the snapshot);
 * - independently authenticated the exact archive and sidecar digests against that authorization,
 *   parsed `matched` from those exact bytes, and authenticated the source/repository binding; and
 * - frozen the archive, then persisted a fresh random witness and the expected tuple outside it before
 *   the transaction. The old writer must remain fenced after this call and throughout activation.
 *
 * This API cannot verify a signature, fence a writer, or prove that the caller followed this
 * contract. UI values, sidecar self-hashes, a boolean, or a test fake are NOT authorization.
 * Do not install this bridge unless every step is implemented in the trusted App composition.
 * Backup has no dependency on sync and performs no network operations in the SQLite transaction.
 */
object BackupIdentityTrustedComposition {
    /** Use once per attempt; persist outside the candidate before staging or pointer publication. */
    fun newWitness(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun authorize(
        attempt: BackupIdentityAttempt,
        matched: ContinuityValidation.Matched,
        authenticatedAliases: Map<String, String>,
        witness: String,
    ): BackupIdentityAuthorization {
        val aliases = BackupIdentityPeerAliases(authenticatedAliases)
        val expected = BackupIdentityContinuityCommit(attempt, matched.takeoverKey, witness,
            backupIdentityAliasDigest(aliases.canonicalBySource))
        backupIdentityValidateExpected(expected)
        return BackupIdentityAuthorization(BackupIdentityAuthorizedSnapshot(matched), aliases, expected)
    }
}

/** Defensive immutable snapshot; compare every Matched field again inside the write transaction. */
internal class BackupIdentityAuthorizedSnapshot(matched: ContinuityValidation.Matched) {
    private val epoch = matched.snapshotEpoch
    private val archive = matched.archiveSha256
    private val sidecar = matched.sidecarSha256
    private val installation = matched.sourceInstallation
    private val key = matched.takeoverKey
    private val repository = matched.state.repository
    private val upload = matched.state.upload
    private val cursors = Collections.unmodifiableList(ArrayList(matched.state.cursors))
    private val watermarks = Collections.unmodifiableList(ArrayList(matched.state.watermarks))
    private val publications = Collections.unmodifiableList(ArrayList(matched.state.publications))
    private val keys = Collections.unmodifiableList(ArrayList(matched.state.processedKeys))

    fun matches(matched: ContinuityValidation.Matched): Boolean = matchesHeader(matched) && matchesState(matched)

    private fun matchesHeader(matched: ContinuityValidation.Matched): Boolean =
        epoch == matched.snapshotEpoch && archive == matched.archiveSha256 && sidecar == matched.sidecarSha256 &&
            installation == matched.sourceInstallation && key == matched.takeoverKey

    private fun matchesState(matched: ContinuityValidation.Matched): Boolean =
        repository == matched.state.repository && upload == matched.state.upload && cursors == matched.state.cursors &&
            watermarks == matched.state.watermarks && publications == matched.state.publications && keys == matched.state.processedKeys
}

internal fun backupIdentityValidateAuthorization(matched: ContinuityValidation.Matched, authorization: BackupIdentityAuthorization) {
    val expected = authorization.expected
    backupIdentityValidateExpected(expected)
    val attempt = expected.attempt
    if (!authorization.snapshot.matches(matched) || expected.takeoverKey != matched.takeoverKey ||
        attempt.instanceId != matched.state.repository.instanceId || attempt.writerId != matched.state.upload.deviceId ||
        attempt.archiveSha256 != matched.archiveSha256 || attempt.sidecarSha256 != matched.sidecarSha256 ||
        expected.aliasesSha256 != backupIdentityAliasDigest(authorization.aliases.canonicalBySource)) {
        throw BackupIdentityException(BackupIdentityFailure.AUTHORIZATION_MISMATCH)
    }
}

internal fun backupIdentityValidateExpected(expected: BackupIdentityContinuityCommit) {
    val attempt = expected.attempt
    if (!backupIdentityUuid(attempt.operationId) || !backupIdentityUuid(attempt.targetInstallationId) ||
        !listOf(attempt.archiveSha256, attempt.sidecarSha256, expected.witness, expected.aliasesSha256).all(::backupIdentityHash) ||
        attempt.instanceId.isBlank() || attempt.writerId.isBlank() ||
        expected.takeoverKey.isBlank()) {
        throw BackupIdentityException(BackupIdentityFailure.AUTHORIZATION_MISMATCH)
    }
}

private fun backupIdentityHash(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
private fun backupIdentityUuid(value: String): Boolean {
    if (value.length != 36) return false
    val parsed = try { UUID.fromString(value) } catch (_: IllegalArgumentException) { return false }
    return parsed.toString() == value && parsed.variant() == 2 && parsed.version() in 1..8
}
