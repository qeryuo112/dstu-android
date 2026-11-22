package com.deepstudent.services.sync.backend

import com.deepstudent.services.sync.api.SyncError
import java.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class SyncPasswordVerifier(val memoryKiB: Long, val iterations: Long, val parallelism: Long,
    val saltHex: String, val digestHex: String)

/** Implementations borrow the password only for the operation or the codec's enclosing run. */
interface SyncCryptography {
    fun createVerifier(password: CharArray): SyncPasswordVerifier
    fun verify(password: CharArray, verifier: SyncPasswordVerifier): Boolean
    fun payload(password: CharArray): SyncPayloadCodec

    suspend fun proveBackupPassword(provider: ObjectProvider, key: String, size: Long, checksum: String, password: CharArray) {
        throw SyncFailure(SyncError.ENCRYPTION_PROOF_REQUIRED)
    }
}

class RepositoryEncryption(private val crypto: SyncCryptography? = null) {
    suspend fun check(provider: ObjectProvider, encrypted: Boolean, password: CharArray?): EncryptionExpectation =
        inspect(provider, encrypted, password).let { (expectation, legacy) ->
            if (legacy != null) invalidMarker()
            expectation
        }

    internal suspend fun inspect(provider: ObjectProvider, encrypted: Boolean, password: CharArray?): MarkerCheck {
        val bytes = provider.getBounded(MARKER_KEY, MARKER_BYTES)
        if (!encrypted) {
            if (bytes != null) throw SyncFailure(SyncError.ENCRYPTED_REPOSITORY)
            return MarkerCheck(EncryptionExpectation(null), null)
        }
        if (bytes == null) invalidMarker()
        val key = password?.takeIf { it.isNotEmpty() } ?: throw SyncFailure(SyncError.ENCRYPTED_REPOSITORY)
        val marker = RepositoryMarker.decode(bytes)
        val verifier = marker.verifier ?: return MarkerCheck(EncryptionExpectation(bytes), marker)
        if (!requireCrypto().verify(key, verifier)) throw SyncFailure(SyncError.ENCRYPTION_PASSWORD_INCORRECT)
        currentCoroutineContext().ensureActive()
        return MarkerCheck(EncryptionExpectation(bytes), null)
    }

    internal suspend fun upgrade(
        provider: ObjectProvider, checked: MarkerCheck, password: CharArray, device: () -> String, recheck: suspend () -> Unit,
    ): EncryptionExpectation {
        val marker = checked.legacy ?: return checked.expectation
        val crypto = requireCrypto()
        val proof = LegacyBackupPassword(provider, crypto)
        checked.expectation.requireUnchanged(provider)
        val empty = proof.prove(password)
        val replacement = marker.encodeV2(crypto.createVerifier(password))
        currentCoroutineContext().ensureActive()
        LegacyMarkerClaim(provider).upgrade(checked.expectation.legacyBytes(), replacement, device()) {
            recheck()
            if (empty) proof.recheckEmpty()
        }
        return EncryptionExpectation(replacement)
    }

    fun codec(password: CharArray?): SyncPayloadCodec = if (password == null) PlainSyncPayload else requireCrypto().payload(password)

    internal fun createMarker(device: String, password: CharArray): ByteArray =
        RepositoryMarker(SyncWireCodec.deviceHash(device), Instant.now().toString(), null).encodeV2(requireCrypto().createVerifier(password))

    private fun requireCrypto(): SyncCryptography = crypto ?: throw SyncFailure(SyncError.CREDENTIAL_UNAVAILABLE)
}

internal data class MarkerCheck(val expectation: EncryptionExpectation, val legacy: RepositoryMarker?)

class EncryptionExpectation internal constructor(marker: ByteArray?) {
    private val marker = marker?.copyOf()
    internal fun legacyBytes(): ByteArray = checkNotNull(marker).copyOf()

    suspend fun requireUnchanged(provider: ObjectProvider) {
        val current = provider.getBounded(MARKER_KEY, MARKER_BYTES)
        if (marker == null && current != null) throw SyncFailure(SyncError.ENCRYPTED_REPOSITORY)
        if (marker != null && (current == null || !current.contentEquals(marker))) invalidMarker()
    }

    fun guardWrites(provider: ObjectProvider): ObjectProvider = object : ObjectProvider by provider {
        override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) {
            requireUnchanged(provider)
            provider.put(key, value, ifAbsent)
        }
    }
}
