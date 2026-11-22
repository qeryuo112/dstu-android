package com.deepstudent.services.sync.backend

import com.deepstudent.services.sync.api.SyncConfiguration
import com.deepstudent.services.sync.api.PairingMode
import com.deepstudent.services.sync.api.SyncError

class SyncFailure(val error: SyncError) : RuntimeException(error.name)

interface ObjectProvider {
    suspend fun get(key: String): ByteArray?
    suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean = false)
    suspend fun list(prefix: String): List<String>
    suspend fun delete(key: String)

    suspend fun getBounded(key: String, maxBytes: Int): ByteArray? = get(key)?.also {
        if (it.size > maxBytes) throw SyncFailure(SyncError.LIMIT_EXCEEDED)
    }

    suspend fun getPrefix(key: String, bytes: Int, objectBytes: Long): ByteArray? = get(key)?.let {
        if (it.size.toLong() != objectBytes || bytes > it.size) throw SyncFailure(SyncError.ENCRYPTED_DATA_INVALID)
        it.copyOf(bytes)
    }

    /** Caller must also hold the interoperable claim; implementations can strengthen the write with an entity tag. */
    suspend fun replaceClaimed(key: String, observed: ByteArray, value: ByteArray): Boolean {
        if (get(key)?.contentEquals(observed) != true) return false
        put(key, value)
        return true
    }

    suspend fun deleteClaimed(key: String, observed: ByteArray): Boolean {
        if (get(key)?.contentEquals(observed) != true) return false
        delete(key)
        return true
    }
}

interface ProviderFactory {
    fun open(endpoint: String, username: String, root: String, password: CharArray): ObjectProvider
}

interface ConfigurationStore {
    fun read(): SyncConfiguration?
    /** Check local identity restrictions before opening a remote provider (CREATE can modify it). */
    fun requirePairingTarget(mode: PairingMode, endpoint: String, root: String) = Unit
    fun verifyCredentialReferences(configuration: SyncConfiguration) = Unit
    fun save(configuration: SyncConfiguration, password: CharArray, encryptionPassword: CharArray? = null)
    fun replaceEncryptionPassword(configuration: SyncConfiguration, password: CharArray)
    fun clear()
}

interface PairingKeyRecovery {
    /** A local, non-mutating probe; throws CREDENTIAL_KEY_LOST only for confirmed key loss. */
    fun requireUsable()

    /** Must recheck for confirmed key loss inside the key store transaction. */
    fun rebuildLostKey()
}

interface DeviceIdentity {
    fun getOrCreate(): String
}
