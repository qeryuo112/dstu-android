package com.deepstudent.app.runtime

import com.deepstudent.platform.security.CredentialKey
import com.deepstudent.platform.security.CredentialStore
import com.deepstudent.platform.security.CredentialStoreError
import com.deepstudent.platform.security.CredentialStoreException
import com.deepstudent.platform.security.SecretBytes
import com.deepstudent.services.providers.data.codex.CodexSessionVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Process-owned connection to the existing Android Keystore-backed private credential store. */
internal class MainCodexSessionVault(private val store: suspend () -> CredentialStore?) : CodexSessionVault {
    override suspend fun load(): ByteArray? {
        var unclaimed: ByteArray? = null
        try {
            return withContext(Dispatchers.IO) {
                readSession()?.also { unclaimed = it }
            }
        } catch (cancelled: CancellationException) {
            unclaimed?.fill(0)
            throw cancelled
        }
    }

    private suspend fun readSession(): ByteArray? = try {
        val secret = requireStore().get(CODEX_SESSION_KEY)
        try {
            secret.use { it.copyOf() }
        } finally {
            secret.close()
        }
    } catch (failure: CredentialStoreException) {
        if (failure.error == CredentialStoreError.NOT_FOUND) null else throw failure
    }

    override suspend fun save(serializedUtf8: ByteArray) {
        try {
            withContext(Dispatchers.IO) {
                val credentials = requireStore()
                val secret = SecretBytes.fromBytes(serializedUtf8)
                try {
                    credentials.put(CODEX_SESSION_KEY, secret)
                } finally {
                    secret.close()
                }
            }
        } finally {
            serializedUtf8.fill(0)
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        requireStore().delete(CODEX_SESSION_KEY)
    }

    private suspend fun requireStore(): CredentialStore =
        store() ?: throw CredentialStoreException(CredentialStoreError.STORAGE_UNAVAILABLE)

    private companion object {
        val CODEX_SESSION_KEY = CredentialKey.of("internal.oauth.openai_codex.session")
    }
}
