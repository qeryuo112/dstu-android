package com.deepstudent.services.backup.backend

import com.deepstudent.services.backup.api.BackupManifest

/** These inputs must be obtained independently of the sidecar, not copied from it. */
data class ContinuityArchiveEvidence(val sha256: String, val bytes: Long, val manifest: BackupManifest) {
    override fun toString() = "ContinuityArchiveEvidence(redacted)"
}

data class ContinuitySourceInstallation(
    val applicationId: String, val versionName: String, val versionCode: Long, val installationId: String,
) {
    override fun toString() = "ContinuitySourceInstallation(redacted)"
}

data class ContinuityRepository(val instanceId: String, val provider: String, val endpointHint: String) {
    override fun toString() = "ContinuityRepository(redacted)"
}

/**
 * These are externally established assertions, NOT a distributed lease or a fence.
 * The caller must maintain quiescence from the snapshot through the eventual transaction commit.
 */
data class ContinuityWriterConfirmation(
    val deviceId: String, val stopped: Boolean, val unchangedSinceSnapshot: Boolean,
) {
    override fun toString() = "ContinuityWriterConfirmation(redacted)"
}

data class ContinuityTrust(
    val archive: ContinuityArchiveEvidence,
    val installation: ContinuitySourceInstallation,
    val repository: ContinuityRepository,
    val writer: ContinuityWriterConfirmation,
    /** Digest of the exact sidecar, obtained and authenticated independently of its bytes. */
    val verifiedSidecarSha256: String,
) {
    override fun toString() = "ContinuityTrust(redacted)"
}

/** Rows carry only the fields of the frozen sync-state contract, never credentials or key material. */
data class ContinuityUpload(val deviceId: String, val lastSeq: Long) {
    override fun toString() = "ContinuityUpload(redacted)"
}
data class ContinuityCursor(val uploaderDeviceId: String, val lastSeq: Long) {
    override fun toString() = "ContinuityCursor(redacted)"
}
data class ContinuityWatermark(val sourceDeviceId: String, val kind: String, val lastAppliedOffset: Long) {
    override fun toString() = "ContinuityWatermark(redacted)"
}
data class ContinuityPublication(val deviceId: String, val kind: String, val operationId: String, val seq: Long) {
    override fun toString() = "ContinuityPublication(redacted)"
}

/** Immutable read-only snapshot: importing it is a separate, future transactional operation. */
class ContinuityState internal constructor(
    val repository: ContinuityRepository,
    val upload: ContinuityUpload,
    val cursors: List<ContinuityCursor>,
    val watermarks: List<ContinuityWatermark>,
    val publications: List<ContinuityPublication>,
    val processedKeys: List<String>,
) {
    override fun toString() = "ContinuityState(redacted)"
}

enum class ContinuityFailure {
    TOO_LARGE, INVALID_JSON, UNSUPPORTED_FORMAT, INVALID_SHAPE, INVALID_STATE,
    TRUST_MISMATCH, WRITER_NOT_CONFIRMED,
}

sealed interface ContinuityValidation {
    /** Does not claim import, duplicate detection, unique-writer transfer or activation. */
    class Matched internal constructor(
        val snapshotEpoch: String,
        val archiveSha256: String,
        val sidecarSha256: String,
        val sourceInstallation: ContinuitySourceInstallation,
        val takeoverKey: String,
        val state: ContinuityState,
    ) : ContinuityValidation {
        override fun toString() = "ContinuityValidation.Matched(redacted)"
    }
    data class Rejected(val reason: ContinuityFailure) : ContinuityValidation
}
