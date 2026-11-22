package com.deepstudent.services.backup.backend

import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.util.UUID

private val HASH = Regex("[0-9a-f]{64}")
private const val MAX_INSTANT = 253402300799L // 9999-12-31T23:59:59Z

internal fun grantValidatePayload(value: ContinuityGrantPayload) {
    grantHttpsUrl(value.repositoryUrl)
    grantIdentity(value.repository.instanceId)
    grantIdentity(value.repository.provider)
    grantEndpointHint(value.repository.endpointHint)
    grantIdentity(value.sourceInstallation.applicationId)
    grantIdentity(value.sourceInstallation.versionName)
    grantIdentity(value.sourceInstallation.installationId)
    grantRequire(value.sourceInstallation.versionCode in 1..JSON_SAFE_INTEGER)
    grantIdentity(value.writerId)
    grantRequire(value.uploadSeq in 0 until Long.MAX_VALUE)
    grantRequire(HASH.matches(value.archiveSha256) && HASH.matches(value.sidecarSha256))
    grantUuid(value.targetInstallationId)
    grantUuid(value.operationId)
    grantRequire(value.issuedAtEpochSecond in 0..MAX_INSTANT && value.expiresAtEpochSecond in 0..MAX_INSTANT)
    grantRequire(value.expiresAtEpochSecond > value.issuedAtEpochSecond &&
        value.expiresAtEpochSecond - value.issuedAtEpochSecond <= GRANT_MAX_LIFETIME_SECONDS)
    grantInventory(value.peerCanonicalIds, value.writerId)
}

internal fun grantCheckExpectation(value: ContinuityGrantExpectation) {
    try {
        grantHttpsUrl(value.repositoryUrl)
        grantUuid(value.targetInstallationId)
        grantUuid(value.operationId)
    } catch (_: ContinuityGrantException) {
        throw ContinuityGrantException(ContinuityGrantFailure.INVALID_EXPECTATION)
    }
}

internal fun grantCheckTime(issued: Long, expires: Long, now: Instant) {
    grantRequire(now >= Instant.ofEpochSecond(issued), ContinuityGrantFailure.NOT_YET_VALID)
    grantRequire(now < Instant.ofEpochSecond(expires), ContinuityGrantFailure.EXPIRED)
}

internal fun grantUuid(value: String) {
    val uuid = try { UUID.fromString(value) } catch (_: IllegalArgumentException) { null }
    grantRequire(value.length == 36 && uuid != null && uuid.toString() == value &&
        uuid.variant() == 2 && uuid.version() in 1..8)
}

internal fun grantIdentity(value: String) {
    grantText(value, 160)
    grantRequire(value != "." && value != ".." && value.none(::grantIdentityInvalidChar))
}

private fun grantIdentityInvalidChar(char: Char): Boolean = char.isWhitespace() || char in "/\\%?#|="

internal fun grantText(value: String, maxBytes: Int) {
    grantRequire(value.isNotBlank() && value.none { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() })
    val bytes = try {
        Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining()
    } catch (_: CharacterCodingException) { throw ContinuityGrantException(ContinuityGrantFailure.INVALID_FORMAT) }
    grantRequire(bytes <= maxBytes, ContinuityGrantFailure.TOO_LARGE)
}

/** Full independent inventory detects even a collision with a device absent from sidecar cursors. */
internal fun grantInventory(peers: List<String>, writerId: String): Map<String, String> {
    grantRequire(peers.size in 1..GRANT_MAX_PEERS, ContinuityGrantFailure.INVALID_INVENTORY)
    val aliases = HashMap<String, String>(peers.size * 2)
    var previous: ByteArray? = null
    for (peer in peers) {
        grantIdentity(peer)
        val bytes = peer.toByteArray(Charsets.UTF_8)
        grantRequire(previous == null || grantCompareUtf8(previous, bytes) < 0, ContinuityGrantFailure.INVALID_INVENTORY)
        previous = bytes
        val hash = grantDigest(bytes).take(16)
        for (alias in listOf(peer, hash)) {
            val owner = aliases.putIfAbsent(alias, peer)
            grantRequire(owner == null || owner == peer, ContinuityGrantFailure.INVALID_INVENTORY)
        }
    }
    grantRequire(writerId in peers, ContinuityGrantFailure.INVALID_INVENTORY)
    return aliases
}

private fun grantCompareUtf8(left: ByteArray, right: ByteArray): Int {
    for (index in 0 until minOf(left.size, right.size)) {
        val delta = (left[index].toInt() and 255) - (right[index].toInt() and 255)
        if (delta != 0) return delta
    }
    return left.size - right.size
}
