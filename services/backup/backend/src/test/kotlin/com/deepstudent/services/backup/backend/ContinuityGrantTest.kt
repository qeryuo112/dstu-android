package com.deepstudent.services.backup.backend

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ContinuityGrantTest {
    private val f = GrantTestFixture()

    @Test fun signedIndependentGrantVerifiesActualFixtureAndBindsSidecar() {
        val verified = f.verify()
        assertEquals(f.repository, verified.repository)
        assertEquals(f.url, verified.repositoryUrl)
        assertEquals(f.payload.sourceInstallation, verified.sourceInstallation)
        assertEquals(5L, verified.uploadSeq)
        assertEquals(f.payload.archiveSha256, verified.archiveSha256)
        assertEquals(f.payload.sidecarSha256, verified.sidecarSha256)
        assertEquals(f.target, verified.targetInstallationId)
        assertEquals(f.operation, verified.operationId)
        val matched = f.validate(verified) as ContinuityValidation.Matched
        assertEquals(mapOf("device-91bc4de0" to "device-91bc4de0"), verified.bind(matched, f.now))
    }

    @Test fun wireFormatIsDeterministicAndStrictlyOrdered() {
        val p = f.payload
        val expected = listOf("dstu-continuity-grant-v1", f.url, p.repository.instanceId, p.repository.provider,
            p.repository.endpointHint, p.sourceInstallation.applicationId, p.sourceInstallation.versionName,
            p.sourceInstallation.versionCode.toString(), p.sourceInstallation.installationId, p.writerId, "5",
            p.archiveSha256, p.sidecarSha256, f.target, f.operation, p.issuedAtEpochSecond.toString(),
            p.expiresAtEpochSecond.toString(), "writer-permanently-revoked", "snapshot-sequence-verified",
            "claim-store-atomic-immutable", "2", "device-7f3a91c2", "device-91bc4de0").joinToString("\n", postfix = "\n")
        assertEquals(expected, String(f.bytes(), Charsets.UTF_8))
        assertEquals(p, ContinuityGrantCodec.decode(expected.toByteArray()))
    }

    @Test fun signatureCannotSupplyOrSelectItsOwnKey() {
        val bytes = f.bytes()
        grantRejected(ContinuityGrantFailure.INVALID_SIGNATURE) { f.verify(bytes, GrantTestKeys.sign(bytes, GrantTestKeys.other)) }
        val wrongPin = ContinuityGrantExpectation(GrantTestKeys.other.public.encoded, f.url, f.target, f.operation)
        grantRejected(ContinuityGrantFailure.INVALID_SIGNATURE) { f.verify(expectation = wrongPin) }
        val badInput = bytes.copyOf().also { it[5] = (it[5].toInt() xor 1).toByte() }
        grantRejected(ContinuityGrantFailure.INVALID_SIGNATURE) { f.verify(badInput, GrantTestKeys.sign(bytes)) }
        val badSignature = GrantTestKeys.sign(bytes).also { it[0] = (it[0].toInt() xor 1).toByte() }
        grantRejected(ContinuityGrantFailure.INVALID_SIGNATURE) { f.verify(bytes, badSignature) }
    }

    @Test fun onlyCanonicalIndependentRsaKeysInAllowedRangeAreAccepted() {
        val tiny = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val ec = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        for (key in listOf(tiny.public.encoded, ec.public.encoded, byteArrayOf(1, 2, 3), GrantTestKeys.admin.public.encoded + 0)) {
            grantRejected(ContinuityGrantFailure.INVALID_KEY) {
                f.verify(expectation = ContinuityGrantExpectation(key, f.url, f.target, f.operation))
            }
        }
    }

    @Test fun keyPinIsDefensivelyCopied() {
        val pin = GrantTestKeys.admin.public.encoded.copyOf()
        val expected = ContinuityGrantExpectation(pin, f.url, f.target, f.operation)
        pin.fill(0)
        f.verify(expectation = expected)
    }

    @Test fun signedBytesAreDefensiveSnapshotsAndCannotLeakThroughToString() {
        val input = f.bytes()
        val signature = GrantTestKeys.sign(input)
        val signed = ContinuityGrantSignedBytes(input, signature)
        input.fill(0)
        signature.fill(0)
        assertEquals("ContinuityGrantSignedBytes(redacted)", signed.toString())
        ContinuityGrantVerifier.verify(signed, f.expectation, f.archive, ByteArrayInputStream(f.archiveBytes), f.sidecar, f.now)
    }

    @Test fun fullAllowedInventoryIsAcceptedAndOversizedInventoryIsRejected() {
        val peers = (0 until GRANT_MAX_PEERS - 1).map { "peer-${it.toString().padStart(5, '0')}" }
        val all = (peers + f.payload.writerId).sorted()
        f.verify(payload = f.bytes(f.payload.copy(peerCanonicalIds = all)))
        grantRejected(ContinuityGrantFailure.INVALID_INVENTORY) {
            f.bytes(f.payload.copy(peerCanonicalIds = (all + "peer-overflow").sorted()))
        }
    }

    @Test fun boundRepositoryTargetAndDurableOperationCannotBeFilledFromGrant() {
        val invalid = listOf(
            ContinuityGrantExpectation(GrantTestKeys.admin.public.encoded, "https://example.org/dav/other/", f.target, f.operation),
            ContinuityGrantExpectation(GrantTestKeys.admin.public.encoded, f.url, "20000000-0000-4000-8000-000000000001", f.operation),
            ContinuityGrantExpectation(GrantTestKeys.admin.public.encoded, f.url, f.target, "20000000-0000-4000-8000-000000000002"),
        )
        for (expectation in invalid) grantRejected(ContinuityGrantFailure.BINDING_MISMATCH) { f.verify(expectation = expectation) }
        val oldHintInsteadOfUrl = ContinuityGrantExpectation(GrantTestKeys.admin.public.encoded, f.hint, f.target, f.operation)
        grantRejected(ContinuityGrantFailure.INVALID_EXPECTATION) { f.verify(expectation = oldHintInsteadOfUrl) }
    }

    @Test fun archiveAndSidecarMustBeActuallyReadAndIndependentlyBound() {
        grantRejected(ContinuityGrantFailure.BINDING_MISMATCH) { f.verify(sidecar = f.sidecar + 0) }
        grantRejected(ContinuityGrantFailure.BINDING_MISMATCH) { f.verify(archive = f.archive.copy(sha256 = "0".repeat(64))) }
        grantRejected(ContinuityGrantFailure.BINDING_MISMATCH) { f.verify(archive = f.archive.copy(bytes = f.archive.bytes - 1)) }
        grantRejected(ContinuityGrantFailure.BINDING_MISMATCH) { f.verify(stream = ByteArrayInputStream(f.archiveBytes + 0)) }
        val changed = f.archiveBytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        grantRejected(ContinuityGrantFailure.BINDING_MISMATCH) { f.verify(stream = ByteArrayInputStream(changed)) }
        grantRejected(ContinuityGrantFailure.BINDING_MISMATCH) { f.verify(stream = ByteArrayInputStream(byteArrayOf())) }
    }

    @Test fun readErrorsAndCancellationAreNotConfusedWithAnAuthorizedGrant() {
        val broken = object : InputStream() { override fun read(): Int = throw IOException("secret") }
        grantRejected(ContinuityGrantFailure.ARCHIVE_IO) { f.verify(stream = broken) }
        val zero = object : InputStream() {
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int) = 0
        }
        grantRejected(ContinuityGrantFailure.ARCHIVE_IO) { f.verify(stream = zero) }
        val cancellation = CancellationException("test")
        try { f.verify(checkCancelled = { throw cancellation }); fail("not cancelled") }
        catch (actual: CancellationException) { assertSame(cancellation, actual) }
    }

    @Test fun timeWindowIsHalfOpenAndBoundToTrustedCallersClock() {
        val p = f.payload
        f.verify(now = Instant.ofEpochSecond(p.issuedAtEpochSecond))
        f.verify(now = Instant.ofEpochSecond(p.expiresAtEpochSecond).minusNanos(1))
        grantRejected(ContinuityGrantFailure.NOT_YET_VALID) { f.verify(now = Instant.ofEpochSecond(p.issuedAtEpochSecond).minusNanos(1)) }
        grantRejected(ContinuityGrantFailure.EXPIRED) { f.verify(now = Instant.ofEpochSecond(p.expiresAtEpochSecond)) }
        val verified = f.verify()
        val matched = f.validate(verified) as ContinuityValidation.Matched
        grantRejected(ContinuityGrantFailure.EXPIRED) { verified.trust(f.archive, Instant.ofEpochSecond(p.expiresAtEpochSecond)) }
        grantRejected(ContinuityGrantFailure.EXPIRED) { verified.bind(matched, Instant.ofEpochSecond(p.expiresAtEpochSecond)) }
        grantRejected(ContinuityGrantFailure.BINDING_MISMATCH) { verified.trust(f.archive.copy(bytes = 1), f.now) }
    }

    @Test fun payloadOverLimitAndExtraDataAreRejected() {
        grantRejected(ContinuityGrantFailure.TOO_LARGE) { f.verify(payload = ByteArray(GRANT_MAX_BYTES + 1), signature = ByteArray(256)) }
        grantRejected(ContinuityGrantFailure.TOO_LARGE) { f.verify(sidecar = ByteArray(CONTINUITY_MAX_BYTES + 1)) }
        val invalid = f.bytes() + "extra\n".toByteArray()
        grantRejected(ContinuityGrantFailure.INVALID_FORMAT) { f.verify(payload = invalid) }
    }

    @Test fun accessorsDoNotLeakAndCollectionsCannotMutate() {
        val grant = f.verify()
        assertFalse(grant.toString().contains("device-"))
        assertFalse(f.payload.toString().contains("https:"))
        assertFalse(f.expectation.toString().contains("https:"))
        assertFalse(ContinuityGrantException(ContinuityGrantFailure.INVALID_FORMAT).toString().contains("device-"))
        try { (grant.peerCanonicalIds as MutableList<String>).clear(); fail("mutable") }
        catch (_: UnsupportedOperationException) { }
        val matched = f.validate(grant) as ContinuityValidation.Matched
        val aliases = grant.bind(matched, f.now)
        try { (aliases as MutableMap<String, String>).clear(); fail("mutable") }
        catch (_: UnsupportedOperationException) { }
    }
}
