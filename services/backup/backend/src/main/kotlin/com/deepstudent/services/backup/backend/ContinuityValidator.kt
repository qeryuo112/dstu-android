package com.deepstudent.services.backup.backend

import java.util.concurrent.CancellationException

/**
 * Validates the frozen legacy-mobile v1 sidecar against independent caller evidence.
 * This parser does not authenticate evidence, read a database, import, or stop any writer.
 */
object ContinuityValidator {
    fun validate(
        sidecar: ByteArray,
        trust: ContinuityTrust,
        checkCancelled: () -> Unit = {},
    ): ContinuityValidation = try {
        continuityRequire(sidecar.size <= CONTINUITY_MAX_BYTES, ContinuityFailure.TOO_LARGE)
        // Copy once so the bytes authenticated here are exactly those parsed below.
        val input = sidecar.copyOf()
        checkCancelled()
        continuityRequire(Regex("[0-9a-f]{64}").matches(trust.verifiedSidecarSha256), ContinuityFailure.TRUST_MISMATCH)
        continuityRequire(digest(input) == trust.verifiedSidecarSha256, ContinuityFailure.TRUST_MISMATCH)
        val root = ContinuityJson.parse(input, checkCancelled)
        root.shape(
            setOf("format", "formatVersion", "status", "snapshot", "source_installation", "identity", "state", "key_policy"),
            setOf("note", "created_at", "generator", "expected"),
        )
        validateContinuityFormat(root)
        validateContinuityMetadata(root)
        validateContinuityTrust(root, trust)
        checkCancelled()
        val state = parseContinuityState(root.objectValue("state"), trust.repository, trust.writer.deviceId, checkCancelled)
        val epoch = trust.archive.manifest.snapshotEpoch
        val key = "continuity-takeover/$epoch/${trust.archive.sha256}"
        continuityRequire(key !in state.processedKeys, ContinuityFailure.INVALID_STATE)
        validateContinuityExpected(root, state, trust.repository, trust.writer.deviceId, key, checkCancelled)
        ContinuityValidation.Matched(epoch, trust.archive.sha256, trust.verifiedSidecarSha256, trust.installation, key, state)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (invalid: ContinuityInvalid) {
        ContinuityValidation.Rejected(invalid.reason)
    }
}
