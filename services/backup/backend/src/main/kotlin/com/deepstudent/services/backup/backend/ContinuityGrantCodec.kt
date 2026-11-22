package com.deepstudent.services.backup.backend

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal const val GRANT_MAX_BYTES = 1024 * 1024
internal const val GRANT_MAX_PEERS = 4096
internal const val GRANT_MAX_LIFETIME_SECONDS = 7 * 24 * 60 * 60L
private const val DOMAIN = "dstu-continuity-grant-v1"
private val DECIMAL_GRANT = Regex("0|[1-9][0-9]*")
private val ASSERTIONS = listOf("writer-permanently-revoked", "snapshot-sequence-verified", "claim-store-atomic-immutable")

/**
 * Sign exactly [encode]'s bytes using SHA256withRSA (RSA PKCS#1 v1.5). The detached signature is
 * raw modulus-sized binary, not base64, JSON, or PEM. Each line is strict UTF-8 followed by one LF;
 * CR, control characters, blank lines and trailing extra data are prohibited. Fields in order:
 *
 * domain `dstu-continuity-grant-v1`; canonical HTTPS repositoryUrl; sidecar repository instanceId,
 * provider and endpointHint; source applicationId, versionName, versionCode, installationId;
 * writerId; uploadSeq; archiveSha256; sidecarSha256; targetInstallationId; operationId;
 * issuedAtEpochSecond; expiresAtEpochSecond; the three literal assertions
 * `writer-permanently-revoked`, `snapshot-sequence-verified`, `claim-store-atomic-immutable`;
 * peer count; then that many canonical device IDs in ascending unsigned UTF-8 byte order.
 * All numbers are unsigned base-10 without leading zeros. IDs and URLs must meet the verifier's
 * bounds and path rules; the key never appears in the signed document. The assertions mean the
 * administrator has permanently revoked old-writer write access, independently checked the
 * snapshot's sequence, and ensured a durable atomic immutable management claim namespace. The
 * inventory is complete and must remain valid through commit. Encoding alone grants no authority.
 */
object ContinuityGrantCodec {
    fun encode(payload: ContinuityGrantPayload): ByteArray {
        val stable = payload.copy(peerCanonicalIds = payload.peerCanonicalIds.toList())
        grantValidatePayload(stable)
        val repository = stable.repository
        val source = stable.sourceInstallation
        val fields = listOf(DOMAIN, stable.repositoryUrl, repository.instanceId, repository.provider, repository.endpointHint,
            source.applicationId, source.versionName, source.versionCode.toString(), source.installationId,
            stable.writerId, stable.uploadSeq.toString(), stable.archiveSha256, stable.sidecarSha256,
            stable.targetInstallationId, stable.operationId, stable.issuedAtEpochSecond.toString(),
            stable.expiresAtEpochSecond.toString()) + ASSERTIONS + stable.peerCanonicalIds.size.toString() + stable.peerCanonicalIds
        val bytes = (fields.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)
        grantRequire(bytes.size <= GRANT_MAX_BYTES, ContinuityGrantFailure.TOO_LARGE)
        return bytes
    }

    internal fun decode(bytes: ByteArray): ContinuityGrantPayload {
        grantRequire(bytes.size <= GRANT_MAX_BYTES, ContinuityGrantFailure.TOO_LARGE)
        val raw = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) { throw ContinuityGrantException(ContinuityGrantFailure.INVALID_FORMAT) }
        grantRequire(raw.endsWith('\n') && '\r' !in raw)
        val lines = raw.dropLast(1).split('\n')
        grantRequire(lines.size >= 21 && lines[0] == DOMAIN)
        grantRequire(lines.subList(17, 20) == ASSERTIONS)
        val count = number(lines[20], GRANT_MAX_PEERS.toLong()).toInt()
        grantRequire(lines.size == 21 + count)
        val payload = ContinuityGrantPayload(
            lines[1], ContinuityRepository(lines[2], lines[3], lines[4]),
            ContinuitySourceInstallation(lines[5], lines[6], number(lines[7]), lines[8]),
            lines[9], number(lines[10]), ContinuityGrantBinding(lines[11], lines[12], lines[13], lines[14]),
            ContinuityGrantValidity(number(lines[15]), number(lines[16])), lines.subList(21, lines.size).toList(),
        )
        grantValidatePayload(payload)
        return payload
    }

    private fun number(value: String, max: Long = Long.MAX_VALUE): Long {
        grantRequire(DECIMAL_GRANT.matches(value))
        val parsed = value.toLongOrNull() ?: throw ContinuityGrantException(ContinuityGrantFailure.INVALID_FORMAT)
        grantRequire(parsed <= max)
        return parsed
    }
}
