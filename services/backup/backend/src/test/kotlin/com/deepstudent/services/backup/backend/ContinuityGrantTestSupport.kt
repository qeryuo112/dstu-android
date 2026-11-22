package com.deepstudent.services.backup.backend

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

internal object GrantTestKeys {
    val admin: KeyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    val other: KeyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    fun sign(bytes: ByteArray, key: KeyPair = admin): ByteArray = Signature.getInstance("SHA256withRSA").run {
        initSign(key.private); update(bytes); sign()
    }
}

internal class GrantTestFixture {
    val now: Instant = Instant.parse("2026-11-22T00:00:00Z")
    val url = "https://example.org/dav/study/"
    val target = "10000000-0000-4000-8000-000000000001"
    val operation = "10000000-0000-4000-8000-000000000002"
    val hint = "webdav.base_url=https://example.org/dav/|root=study"
    val repository = ContinuityRepository("default", "WebDAV", hint)
    val archive = ContinuityFixture.trust.archive
    val archiveBytes: ByteArray = findArchive().readBytes()
    val sidecar: ByteArray = ContinuityFixture.input(ContinuityFixture.root.removeKey("expected").changeState("instanceBinding") { rows ->
        rows.changeRow(0) { it.change("provider", JsonPrimitive(repository.provider)).change("endpoint_hint", JsonPrimitive(hint)) }
    })
    val expectation get() = ContinuityGrantExpectation(GrantTestKeys.admin.public.encoded, url, target, operation)
    val payload get() = ContinuityGrantPayload(
        url, repository, ContinuityFixture.trust.installation, "device-7f3a91c2", 5L,
        ContinuityGrantBinding(archive.sha256, grantDigest(sidecar), target, operation),
        ContinuityGrantValidity(now.epochSecond - 30, now.epochSecond + 3600), listOf("device-7f3a91c2", "device-91bc4de0"),
    )
    fun bytes(payload: ContinuityGrantPayload = this.payload): ByteArray = ContinuityGrantCodec.encode(payload)
    fun verify(
        payload: ByteArray = bytes(), signature: ByteArray = GrantTestKeys.sign(payload),
        expectation: ContinuityGrantExpectation = this.expectation,
        archive: ContinuityArchiveEvidence = this.archive, stream: InputStream = ByteArrayInputStream(archiveBytes),
        sidecar: ByteArray = this.sidecar, now: Instant = this.now, checkCancelled: () -> Unit = {},
    ) = ContinuityGrantVerifier.verify(ContinuityGrantSignedBytes(payload, signature), expectation, archive, stream, sidecar, now, checkCancelled)
    fun validate(grant: VerifiedContinuityGrant): ContinuityValidation =
        ContinuityValidator.validate(sidecar, grant.trust(archive, now))

    private fun findArchive(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val file = File(dir, "fixtures/legacy-mobile/legacy-mobile-continuity-continuity-v1.zip")
            if (file.isFile) return file
            dir = dir.parentFile
        }
        error("fixture not found")
    }
}

internal inline fun grantRejected(reason: ContinuityGrantFailure, block: () -> Unit) {
    try { block(); fail("expected $reason") }
    catch (failure: ContinuityGrantException) { assertEquals(reason, failure.reason) }
}
