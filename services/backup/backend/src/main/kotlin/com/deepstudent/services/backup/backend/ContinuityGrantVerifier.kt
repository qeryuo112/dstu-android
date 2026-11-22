package com.deepstudent.services.backup.backend

import java.io.IOException
import java.io.InputStream
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.concurrent.CancellationException

private const val MAX_KEY_BYTES = 8192
// Include archive framing on top of the existing 20 GiB uncompressed payload limit.
private const val MAX_ARCHIVE_BYTES = 22L * 1024 * 1024 * 1024

/** Signature, exact local expectations and actual archive/sidecar bytes are all mandatory. */
object ContinuityGrantVerifier {
    /**
     * The caller opens the exact privately held archive that produced [archive] and supplies its
     * preflight evidence. This method reads, but does not close, [archiveStream]. The same bytes
     * must be protected against replacement until the restore commits. [sidecar] is copied before
     * hashing; use that same private sidecar with ContinuityValidator. [now] must come from a
     * trusted clock, never the document: callers must reverify with current time before claim and
     * cold-start activation. Verified time plus an existing durable activation receipt is a
     * separate post-activation crash-reentry policy, not authority from an expired grant.
     *
     * Only [ContinuityGrantException.reason] is safe to log. Cancellation propagates; no input,
     * public key, credentials, upstream exception or stream contents are retained in failures.
     */
    fun verify(
        signed: ContinuityGrantSignedBytes,
        expectation: ContinuityGrantExpectation,
        archive: ContinuityArchiveEvidence,
        archiveStream: InputStream,
        sidecar: ByteArray,
        now: Instant,
        checkCancelled: () -> Unit = {},
    ): VerifiedContinuityGrant {
        grantRequire(sidecar.size <= CONTINUITY_MAX_BYTES, ContinuityGrantFailure.TOO_LARGE)
        val input = signed.payloadCopy()
        val signature = signed.signatureCopy()
        val sidecarCopy = sidecar.copyOf()
        checkCancelled()
        val key = grantKey(expectation.publicKeyBytes())
        grantCheckExpectation(expectation)
        grantVerifySignature(key, input, signature)
        val payload = ContinuityGrantCodec.decode(input)
        grantCheckTime(payload.issuedAtEpochSecond, payload.expiresAtEpochSecond, now)
        grantRequire(payload.repositoryUrl == expectation.repositoryUrl &&
            payload.targetInstallationId == expectation.targetInstallationId && payload.operationId == expectation.operationId,
            ContinuityGrantFailure.BINDING_MISMATCH)
        grantRequire(archive.bytes in 1..MAX_ARCHIVE_BYTES && archive.sha256 == payload.archiveSha256 &&
            grantDigest(sidecarCopy) == payload.sidecarSha256, ContinuityGrantFailure.BINDING_MISMATCH)
        val observed = grantHashArchive(archiveStream, archive.bytes, checkCancelled)
        grantRequire(observed == payload.archiveSha256, ContinuityGrantFailure.BINDING_MISMATCH)
        checkCancelled()
        return VerifiedContinuityGrant(payload, now, archive.bytes)
    }
}

private fun grantKey(encoded: ByteArray): RSAPublicKey {
    grantRequire(encoded.size in 1..MAX_KEY_BYTES, ContinuityGrantFailure.INVALID_KEY)
    val key = try { KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(encoded)) as? RSAPublicKey }
    catch (_: GeneralSecurityException) { null }
    grantRequire(key != null, ContinuityGrantFailure.INVALID_KEY)
    key!!
    grantRequire(key.encoded.contentEquals(encoded) && key.modulus.bitLength() in 2048..4096 && key.publicExponent.bitLength() >= 17 &&
        key.publicExponent.testBit(0), ContinuityGrantFailure.INVALID_KEY)
    return key
}

private fun grantVerifySignature(key: RSAPublicKey, payload: ByteArray, signature: ByteArray) {
    grantRequire(signature.size == (key.modulus.bitLength() + 7) / 8, ContinuityGrantFailure.INVALID_SIGNATURE)
    val valid = try {
        Signature.getInstance("SHA256withRSA").run { initVerify(key); update(payload); verify(signature) }
    } catch (_: GeneralSecurityException) { false }
    grantRequire(valid, ContinuityGrantFailure.INVALID_SIGNATURE)
}

private fun grantHashArchive(input: InputStream, expected: Long, cancel: () -> Unit): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var count = 0L
    try {
        while (true) {
            cancel()
            // Read at most one byte past the expected end, never an unbounded hostile stream.
            val amount = minOf(buffer.size.toLong(), expected - count + 1).toInt()
            val read = input.read(buffer, 0, amount)
            if (read == -1) break
            if (read == 0) throw IOException()
            count += read
            grantRequire(count <= expected, ContinuityGrantFailure.BINDING_MISMATCH)
            digest.update(buffer, 0, read)
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (rejected: ContinuityGrantException) { throw rejected }
    catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw ContinuityGrantException(ContinuityGrantFailure.ARCHIVE_IO)
    } catch (_: Exception) { throw ContinuityGrantException(ContinuityGrantFailure.ARCHIVE_IO) }
    grantRequire(count == expected, ContinuityGrantFailure.BINDING_MISMATCH)
    return digest.digest().grantHex()
}

internal fun grantDigest(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).grantHex()
private fun ByteArray.grantHex(): String {
    val alphabet = "0123456789abcdef"
    val chars = CharArray(size * 2)
    for (index in indices) {
        val value = this[index].toInt() and 0xff
        chars[index * 2] = alphabet[value ushr 4]
        chars[index * 2 + 1] = alphabet[value and 15]
    }
    return String(chars)
}
