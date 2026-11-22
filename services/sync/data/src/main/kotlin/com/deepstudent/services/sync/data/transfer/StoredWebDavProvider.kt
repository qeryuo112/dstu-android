package com.deepstudent.services.sync.data.transfer

import com.deepstudent.platform.security.SecretBytes
import com.deepstudent.services.sync.api.SyncConfiguration
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.ObjectProvider
import com.deepstudent.services.sync.backend.ProviderFactory
import com.deepstudent.services.sync.backend.SyncFailure
import com.deepstudent.services.sync.data.LocalSyncStore
import java.nio.ByteBuffer
import java.nio.CharBuffer

class StoredWebDavProvider(private val store: LocalSyncStore, private val factory: ProviderFactory) {
    fun open(configuration: SyncConfiguration = store.read() ?: throw SyncFailure(SyncError.INVALID_CONFIGURATION)): ObjectProvider {
        val password = decodePassword(store.password(configuration))
        return try {
            factory.open(configuration.endpoint, configuration.username, configuration.root, password)
        } finally { password.fill('\u0000') }
    }
}

class StoredEncryptionPassword(private val store: LocalSyncStore) {
    suspend fun <T> use(configuration: SyncConfiguration, block: suspend (CharArray?) -> T): T {
        if (!configuration.encrypted) return block(null)
        val password = decodePassword(store.encryptionPassword(configuration))
        return try { block(password) } finally { password.fill('\u0000') }
    }
}

private fun decodePassword(secret: SecretBytes): CharArray {
    val decoded: CharBuffer = try {
        secret.use { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(it)) }
    } catch (_: java.nio.charset.CharacterCodingException) {
        throw SyncFailure(SyncError.CREDENTIAL_INTEGRITY)
    } finally { secret.close() }
    try { return CharArray(decoded.remaining()).also(decoded::get) }
    finally { if (decoded.hasArray()) decoded.array().fill('\u0000') }
}
