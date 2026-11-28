package com.deepstudent.app.runtime

import com.deepstudent.platform.security.CredentialStoreError
import com.deepstudent.platform.security.CredentialStoreException
import com.deepstudent.platform.security.KeyWrapper
import com.deepstudent.platform.security.SecurityFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class S305CodexSessionVaultTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun retainsOnlyEncryptedRecordAndCanReopenOrDeleteIt() = runBlocking {
        val serialized = "synthetic-codex-session-should-not-occur-in-the-credential-file".toByteArray()
        SecurityFactory.create(directory.root, JvmKeyWrapper).use { ports ->
            val vault = MainCodexSessionVault { ports.credentials }
            assertNull(vault.load())
            val transferred = serialized.copyOf()
            vault.save(transferred)
            assertTrue(transferred.all { it == 0.toByte() })
            val record = directory.root.walkTopDown().single { it.extension == "dsc" }
            assertFalse(record.readBytes().decodeToString().contains(serialized.decodeToString()))
        }
        SecurityFactory.create(directory.root, JvmKeyWrapper).use { ports ->
            val vault = MainCodexSessionVault { ports.credentials }
            val reopened = checkNotNull(vault.load())
            try {
                assertArrayEquals(serialized, reopened)
            } finally {
                reopened.fill(0)
            }
            vault.clear()
            vault.clear()
            assertNull(vault.load())
            assertTrue(directory.root.walkTopDown().none { it.extension == "dsc" })
        }
    }

    @Test
    fun unopenedKeystoreIsNotReportedAsASignedOutAccount() = runBlocking {
        val thrown = runCatching { MainCodexSessionVault { null }.load() }.exceptionOrNull() as CredentialStoreException
        assertEquals(CredentialStoreError.STORAGE_UNAVAILABLE, thrown.error)
    }

    /** Only wraps the JVM test's synthetic master; the production key comes from Android Keystore. */
    private object JvmKeyWrapper : KeyWrapper {
        override fun wrap(plaintext: ByteArray): ByteArray = plaintext.copyOf()
        override fun unwrap(wrapped: ByteArray): ByteArray = wrapped.copyOf()
    }
}
