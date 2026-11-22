package com.deepstudent.services.sync.backend

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.sync.api.SyncError
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val CLAIM_PREFIX = "$MARKER_KEY.pending/"
private const val LEASE_KEY = "$MARKER_KEY.lease"
private const val TTL_SECONDS = 60L

internal class LegacyMarkerClaim(private val provider: ObjectProvider, private val clock: Clock = Clock.systemUTC()) {
    suspend fun upgrade(expected: ByteArray, replacement: ByteArray, deviceId: String, recheck: suspend () -> Unit) {
        val now = clock.instant()
        val expiry = now.plusSeconds(TTL_SECONDS)
        val nonce = UUID.randomUUID().toString()
        val registration = "$CLAIM_PREFIX$nonce.json"
        val lease = claimBytes(SyncWireCodec.deviceHash(deviceId), nonce, now, expiry)
        var acquired = false
        var uncertainWrite = false
        try {
            provider.put(registration, lease, ifAbsent = true)
            requireRegistration(registration, lease)
            requireMarker(expected)
            acquired = true
            acquire(lease)
            requireLease(lease, expiry)
            requireMarker(expected)
            recheck()
            requireRegistration(registration, lease)
            requireLease(lease, expiry)
            requireMarker(expected)
            currentCoroutineContext().ensureActive()
            if (!clock.instant().isBefore(expiry)) conflict()
            uncertainWrite = true
            if (!provider.replaceClaimed(MARKER_KEY, expected, replacement)) {
                uncertainWrite = false
                conflict()
            }
            if (read(MARKER_KEY)?.contentEquals(replacement) != true) uncertain()
            requireLease(lease, expiry)
            uncertainWrite = false
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            if (uncertainWrite) uncertain()
            throw failure
        } finally {
            if (!uncertainWrite) cleanup(registration, lease, acquired)
        }
        if (read(registration) != null) uncertain()
    }

    private suspend fun acquire(lease: ByteArray) {
        val previous = read(LEASE_KEY)
        if (previous == null) {
            try { provider.put(LEASE_KEY, lease, ifAbsent = true) }
            catch (failure: SyncFailure) { if (failure.error == SyncError.ALREADY_EXISTS) conflict() else throw failure }
        } else if (expiry(previous) > clock.instant() || !provider.replaceClaimed(LEASE_KEY, previous, lease)) {
            conflict()
        }
    }

    private suspend fun requireRegistration(key: String, lease: ByteArray) {
        if (provider.list(CLAIM_PREFIX) != listOf(key) || read(key)?.contentEquals(lease) != true) conflict()
    }

    private suspend fun requireLease(lease: ByteArray, expires: Instant) {
        if (read(LEASE_KEY)?.contentEquals(lease) != true || !clock.instant().isBefore(expires)) conflict()
    }

    private suspend fun requireMarker(expected: ByteArray) {
        if (read(MARKER_KEY)?.contentEquals(expected) != true) conflict()
    }

    private suspend fun cleanup(key: String, lease: ByteArray, acquired: Boolean) = withContext(NonCancellable) {
        if (acquired) runCatching { withTimeout(5_000) { provider.deleteClaimed(LEASE_KEY, lease) } }
        runCatching { withTimeout(5_000) { provider.deleteClaimed(key, lease) } }
        Unit
    }

    private suspend fun read(key: String) = provider.getBounded(key, MARKER_BYTES)

    private fun expiry(bytes: ByteArray): Instant = try {
        val data = WireValues.parse(bytes)
        require(WireValues.unsigned(data["format_version"], 1uL) == 1uL)
        require(WireValues.text(data["device_id"]).isNotBlank())
        val nonce = WireValues.text(data["nonce"])
        require(UUID.fromString(nonce).toString() == nonce)
        val created = Instant.parse(WireValues.text(data["created_at"]))
        Instant.parse(WireValues.text(data["expires_at"])).also { require(it > created) }
    } catch (_: Exception) { conflict() }

    private fun claimBytes(device: String, nonce: String, created: Instant, expires: Instant): ByteArray =
        WirePretty.encode(JsonValue.Object.Builder().put("format_version", 1).put("device_id", device).put("nonce", nonce)
            .put("created_at", created.toString()).put("expires_at", expires.toString()).build())
}

private fun conflict(): Nothing = throw SyncFailure(SyncError.ENCRYPTION_CLAIM_CONFLICT)
private fun uncertain(): Nothing = throw SyncFailure(SyncError.ENCRYPTION_UPGRADE_UNCERTAIN)
