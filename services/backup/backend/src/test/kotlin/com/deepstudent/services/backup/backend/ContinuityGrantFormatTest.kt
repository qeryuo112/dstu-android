package com.deepstudent.services.backup.backend

import java.security.KeyPairGenerator
import org.junit.Assert.*
import org.junit.Test

class ContinuityGrantFormatTest {
    private val f = GrantTestFixture()

    @Test fun signedDocumentRejectsMalformedAndNoncanonicalWireRepresentations() {
        val lines = f.bytes().toString(Charsets.UTF_8).dropLast(1).split('\n')
        val variants = listOf(
            lines.joinToString("\r\n", postfix = "\r\n").toByteArray(),
            lines.joinToString("\n").toByteArray(),
            replace(lines, 0, "dstu-continuity-grant-v2"),
            replace(lines, 7, "01"), replace(lines, 7, "+1"), replace(lines, 7, "9223372036854775808"),
            replace(lines, 10, "-1"), replace(lines, 10, "0.0"), replace(lines, 15, "01"),
            replace(lines, 17, "writer-maybe-revoked"), replace(lines, 18, "snapshot-unverified"),
            replace(lines, 19, "claim-store-possibly-atomic"), replace(lines, 20, "3"),
            replace(lines, 21, "a\nb"), f.bytes() + "key=embedded\n".toByteArray(),
            f.bytes().copyOf().also { it[25] = 0xff.toByte() },
        )
        for (input in variants) grantRejected(ContinuityGrantFailure.INVALID_FORMAT) {
            f.verify(payload = input, signature = GrantTestKeys.sign(input))
        }
    }

    @Test fun timesAreLimitedToSevenDaysAndUnambiguous() {
        val p = f.payload
        grantRejected(ContinuityGrantFailure.INVALID_FORMAT) { f.bytes(p.copy(validity = p.validity.copy(expiresAtEpochSecond = p.issuedAtEpochSecond))) }
        grantRejected(ContinuityGrantFailure.INVALID_FORMAT) { f.bytes(p.copy(validity = p.validity.copy(expiresAtEpochSecond = p.issuedAtEpochSecond + GRANT_MAX_LIFETIME_SECONDS + 1))) }
        grantRejected(ContinuityGrantFailure.INVALID_FORMAT) { f.bytes(p.copy(validity = p.validity.copy(issuedAtEpochSecond = -1))) }
        grantRejected(ContinuityGrantFailure.INVALID_FORMAT) { f.bytes(p.copy(validity = p.validity.copy(expiresAtEpochSecond = Long.MAX_VALUE))) }
        f.verify(payload = f.bytes(p.copy(validity = p.validity.copy(expiresAtEpochSecond = p.issuedAtEpochSecond + GRANT_MAX_LIFETIME_SECONDS))))
    }

    @Test fun urlIsHttpsCanonicalIndependentAndCannotContainCredentialsOrAmbiguousPaths() {
        val urls = listOf(
            "http://example.org/dav/", "https://user:pass@example.org/dav/", "https://example.org/dav/?token=x",
            "https://example.org/dav/#x", "https://EXAMPLE.org/dav/", "https://example.org:443/dav/",
            "https://example.org:000444/dav/", "https://example.org:0/dav/", "https://example.org/dav/../evil/",
            "https://example.org/dav//evil", "https://example.org/dav/%2E%2E/evil", "https://example.org/dav/%2Froot",
            "https://example.org/dav/%5Croot", "https://example.org/dav/%252E", "https://example.org/dav/%00", "https://example.org/dav/%zz",
            "https://example.org/dav/%2froot", "https://example.org/dav/with space", "https://example.org", "https://example.org/dav",
        )
        for (url in urls) {
            grantRejected(ContinuityGrantFailure.INVALID_FORMAT) { f.bytes(f.payload.copy(repositoryUrl = url)) }
            grantRejected(ContinuityGrantFailure.INVALID_EXPECTATION) {
                f.verify(expectation = ContinuityGrantExpectation(GrantTestKeys.admin.public.encoded, url, f.target, f.operation))
            }
        }
        val p = f.payload.copy(repositoryUrl = "https://example.org/dav/%E4%B8%AD/hello%20world/")
        f.bytes(p)
    }

    @Test fun hintRemainsDistinctButCannotLeakCredentialsOrEscapeRoot() {
        val hints = listOf(
            "webdav.base_url=https://user:secret@example.org/dav/|root=study",
            "webdav.base_url=https://example.org/dav/?token=x|root=study",
            "webdav.base_url=https://example.org/dav/|webdav.password=secret|root=study",
            "webdav.base_url=https://example.org/dav/|access_token=secret|root=study",
            "webdav.base_url=https://example.org/dav/|root=../other",
            "webdav.base_url=https://example.org/dav/|root=a//b",
            "webdav.base_url=https://example.org/dav/|root=a\\b",
            "webdav.base_url=https://example.org/dav/%2F/|root=study",
        )
        for (hint in hints) grantRejected(ContinuityGrantFailure.INVALID_FORMAT) {
            f.bytes(f.payload.copy(repository = f.repository.copy(endpointHint = hint)))
        }
        f.bytes(f.payload.copy(repository = f.repository.copy(endpointHint = "webdav.base_url=https://example.org/dav/|root=中文 空格")))
    }

    @Test fun aliasesAndIdsCannotBePathsControlsOrAmbiguousUuids() {
        val invalidIds = listOf("..", ".", "../evil", "a/b", "a\\b", "a%2Fb", "a?b", "a#b", "a|b", "a=b", "a b", "a\nsecret", "a\u202eb")
        for (id in invalidIds) grantRejected(ContinuityGrantFailure.INVALID_FORMAT) {
            f.bytes(f.payload.copy(writerId = id, peerCanonicalIds = listOf(id)))
        }
        for (id in listOf("1-1-1-1-1", "ABCDEF00-0000-4000-8000-000000000001", "00000000-0000-0000-0000-000000000000")) {
            grantRejected(ContinuityGrantFailure.INVALID_FORMAT) { f.bytes(f.payload.copy(binding = f.payload.binding.copy(targetInstallationId = id))) }
        }
    }

    @Test fun rsa4096IsSupportedAsWellAsRsa2048() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(4096) }.generateKeyPair()
        val bytes = f.bytes()
        val expected = ContinuityGrantExpectation(pair.public.encoded, f.url, f.target, f.operation)
        f.verify(bytes, GrantTestKeys.sign(bytes, pair), expectation = expected)
    }

    private fun replace(lines: List<String>, index: Int, new: String): ByteArray = lines.toMutableList().also {
        it[index] = new
    }.joinToString("\n", postfix = "\n").toByteArray()
}
