package com.deepstudent.services.backup.backend

import java.io.ByteArrayInputStream
import java.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ContinuityGrantBindingTest {
    private val f = GrantTestFixture()

    @Test fun everySignedSourceRepositoryAndSequenceMustMatchTheValidatedSidecar() {
        val payload = f.payload
        val changes = listOf(
            payload.copy(repository = payload.repository.copy(instanceId = "another")),
            payload.copy(repository = payload.repository.copy(provider = "webdav")),
            payload.copy(repository = payload.repository.copy(endpointHint = "webdav.base_url=https://other.example/dav|root=study")),
            payload.copy(sourceInstallation = payload.sourceInstallation.copy(applicationId = "other.app")),
            payload.copy(sourceInstallation = payload.sourceInstallation.copy(versionName = "1.0.0")),
            payload.copy(sourceInstallation = payload.sourceInstallation.copy(versionCode = 7)),
            payload.copy(sourceInstallation = payload.sourceInstallation.copy(installationId = "other-installation")),
            payload.copy(writerId = "other-writer", peerCanonicalIds = listOf("device-7f3a91c2", "device-91bc4de0", "other-writer")),
            payload.copy(uploadSeq = 4),
        )
        val correct = f.verify()
        val matched = f.validate(correct) as ContinuityValidation.Matched
        for (change in changes) {
            val verified = f.verify(payload = f.bytes(change))
            grantRejected(ContinuityGrantFailure.BINDING_MISMATCH) { verified.bind(matched, f.now) }
            if (change.uploadSeq == payload.uploadSeq) {
                assertTrue("sidecar must also reject mismatched trust", f.validate(verified) is ContinuityValidation.Rejected)
            }
        }
    }

    @Test fun mismatchedArchiveOrSidecarHashesCannotBecomeTrust() {
        val p = f.payload
        for (change in listOf(p.copy(binding = p.binding.copy(archiveSha256 = "0".repeat(64))), p.copy(binding = p.binding.copy(sidecarSha256 = "0".repeat(64))))) {
            grantRejected(ContinuityGrantFailure.BINDING_MISMATCH) { f.verify(payload = f.bytes(change)) }
        }
        val original = f.verify()
        grantRejected(ContinuityGrantFailure.BINDING_MISMATCH) {
            original.trust(f.archive.copy(sha256 = "0".repeat(64)), f.now)
        }
    }

    @Test fun missingAndAmbiguousInventoryIsRejectedInsteadOfGuessing() {
        val grant = f.verify(payload = f.bytes(f.payload.copy(peerCanonicalIds = listOf("device-7f3a91c2"))))
        val matched = f.validate(grant) as ContinuityValidation.Matched
        grantRejected(ContinuityGrantFailure.INVALID_INVENTORY) { grant.bind(matched, f.now) }
        val hash = grantDigest("device-91bc4de0".toByteArray()).take(16)
        val collision = listOf("device-7f3a91c2", "device-91bc4de0", hash).sorted()
        grantRejected(ContinuityGrantFailure.INVALID_INVENTORY) { f.bytes(f.payload.copy(peerCanonicalIds = collision)) }
        grantRejected(ContinuityGrantFailure.INVALID_INVENTORY) {
            f.bytes(f.payload.copy(peerCanonicalIds = listOf("device-91bc4de0", "device-7f3a91c2")))
        }
        grantRejected(ContinuityGrantFailure.INVALID_INVENTORY) {
            f.bytes(f.payload.copy(peerCanonicalIds = listOf("device-91bc4de0")))
        }
    }

    @Test fun untouchedFrozenLocalSidecarCanBeAdminBoundToApprovedWebDavWithoutRewritingHint() {
        val original = ContinuityFixture.bytes
        val sourceRepository = ContinuityFixture.trust.repository
        val payload = f.payload.copy(repository = sourceRepository,
            binding = f.payload.binding.copy(sidecarSha256 = grantDigest(original)))
        val grant = f.verify(payload = f.bytes(payload), sidecar = original)
        assertEquals("file:///deep-student/local/sync", grant.repository.endpointHint)
        assertEquals(f.url, grant.repositoryUrl)
        val matched = ContinuityValidator.validate(original, grant.trust(f.archive, f.now)) as ContinuityValidation.Matched
        assertEquals(mapOf("device-91bc4de0" to "device-91bc4de0"), grant.bind(matched, f.now))
    }

    @Test fun hashedSourceAliasesAreResolvedOnlyFromSignedCompleteInventory() {
        val peer = "device-91bc4de0"
        val hash = grantDigest(peer.toByteArray()).take(16)
        val root = ContinuityFixture.root.removeKey("expected")
            .changeState("instanceBinding") { rows -> rows.changeRow(0) {
                it.change("provider", JsonPrimitive(f.repository.provider)).change("endpoint_hint", JsonPrimitive(f.hint))
            } }
            .changeState("consumeCursor") { rows -> rows.changeRow(0) { it.change("uploader_device_id", JsonPrimitive(hash)) } }
        val sidecar = ContinuityFixture.input(root)
        val bytes = f.bytes(f.payload.copy(binding = f.payload.binding.copy(sidecarSha256 = grantDigest(sidecar))))
        val verified = f.verify(payload = bytes, sidecar = sidecar, stream = ByteArrayInputStream(f.archiveBytes))
        val matched = ContinuityValidator.validate(sidecar, verified.trust(f.archive, f.now)) as ContinuityValidation.Matched
        assertEquals(mapOf(hash to peer), verified.bind(matched, f.now))
    }

    @Test fun checkedMatchedObjectsCannotBeSwappedBetweenGrants() {
        val verified = f.verify()
        val matched = f.validate(verified) as ContinuityValidation.Matched
        val state = matched.state
        val cases = listOf(
            ContinuityValidation.Matched(matched.snapshotEpoch, "f".repeat(64), matched.sidecarSha256, matched.sourceInstallation, matched.takeoverKey, state),
            ContinuityValidation.Matched(matched.snapshotEpoch, matched.archiveSha256, "f".repeat(64), matched.sourceInstallation, matched.takeoverKey, state),
            ContinuityValidation.Matched(matched.snapshotEpoch, matched.archiveSha256, matched.sidecarSha256, matched.sourceInstallation.copy(installationId = "bad"), matched.takeoverKey, state),
            ContinuityValidation.Matched("other", matched.archiveSha256, matched.sidecarSha256, matched.sourceInstallation, matched.takeoverKey, state),
            ContinuityValidation.Matched(matched.snapshotEpoch, matched.archiveSha256, matched.sidecarSha256, matched.sourceInstallation, "wrong", state),
            ContinuityValidation.Matched(matched.snapshotEpoch, matched.archiveSha256, matched.sidecarSha256, matched.sourceInstallation, matched.takeoverKey,
                ContinuityState(state.repository, state.upload.copy(lastSeq = 6), state.cursors, state.watermarks, state.publications, state.processedKeys)),
        )
        for (invalid in cases) grantRejected(ContinuityGrantFailure.BINDING_MISMATCH) { verified.bind(invalid, f.now) }
    }

    @Test fun verifiedObjectsDoNotOverrideTimeChecks() {
        val grant = f.verify()
        val matched = f.validate(grant) as ContinuityValidation.Matched
        grantRejected(ContinuityGrantFailure.NOT_YET_VALID) {
            grant.bind(matched, Instant.ofEpochSecond(grant.issuedAtEpochSecond).minusSeconds(1))
        }
    }
}
