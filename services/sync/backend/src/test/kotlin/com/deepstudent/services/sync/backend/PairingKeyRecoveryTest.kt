package com.deepstudent.services.sync.backend

import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.services.sync.api.PairingMode
import com.deepstudent.services.sync.api.PairingRequest
import com.deepstudent.services.sync.api.SyncConfiguration
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.api.SyncResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingKeyRecoveryTest {
    @Test fun lostKeyBlocksReadsAndRemoteCreationUntilExplicitRecovery() = runBlocking {
        val fixture = Fixture()
        fixture.keyError = SyncError.CREDENTIAL_KEY_LOST
        assertEquals(SyncResult.Failure(SyncError.CREDENTIAL_KEY_LOST), fixture.service.current())
        val request = PairingRequest("https://private.invalid", "private-user", "private-root", "private-pw".toCharArray())
        assertEquals(SyncResult.Failure(SyncError.CREDENTIAL_KEY_LOST), fixture.service.pair(PairingMode.CREATE, request))
        assertTrue(request.password.all { it == '\u0000' })
        assertEquals(SyncResult.Failure(SyncError.CREDENTIAL_KEY_LOST), fixture.service.clear())
        assertEquals(0, fixture.remoteOpens)
        assertEquals(0, fixture.clears)
        assertEquals(SyncResult.Success(Unit), fixture.service.resetCredentialsAfterKeyLoss())
        assertEquals(SyncResult.Success(null), fixture.service.current())
        assertEquals(1, fixture.rebuilds)
        assertEquals(1, fixture.clears)
        assertEquals("local-device", fixture.device)
        assertEquals(0, fixture.remoteOpens)
        fixture.log.chunked(2).forEach { records ->
            assertEquals(2, records.size)
            assertNotNull(records[0].correlationId)
            assertEquals(records[0].correlationId, records[1].correlationId)
            assertFalse(records.toString().contains("private-"))
        }
    }

    @Test fun failedOrUnnecessaryRecoveryDoesNotClearConfiguration() = runBlocking {
        val fixture = Fixture()
        assertEquals(SyncResult.Success(fixture.config), fixture.service.current())
        assertEquals(SyncResult.Failure(SyncError.CREDENTIAL_UNAVAILABLE), fixture.service.resetCredentialsAfterKeyLoss())
        fixture.keyError = SyncError.CREDENTIAL_KEY_LOST
        fixture.rebuildError = SyncError.STORAGE
        assertEquals(SyncResult.Failure(SyncError.STORAGE), fixture.service.resetCredentialsAfterKeyLoss())
        assertNotNull(fixture.config)
        assertEquals(0, fixture.clears)
        assertEquals(0, fixture.rebuilds)
    }

    @Test fun restoredKeyWithDanglingReferencesDoesNotReportConnected() = runBlocking {
        val fixture = Fixture()
        fixture.keyError = SyncError.CREDENTIAL_KEY_LOST
        fixture.clearError = SyncError.STORAGE
        assertEquals(SyncResult.Failure(SyncError.STORAGE), fixture.service.resetCredentialsAfterKeyLoss())
        assertEquals(SyncResult.Failure(SyncError.CREDENTIAL_UNAVAILABLE), fixture.service.current())
    }

    private class Fixture : ConfigurationStore, DeviceIdentity, ProviderFactory, PairingKeyRecovery, Logger {
        val device = "local-device"
        var config: SyncConfiguration? = SyncConfiguration("https://private.invalid", "private-user", "private-root", device, "repo")
        var remoteOpens = 0
        var clears = 0
        var rebuilds = 0
        var keyError: SyncError? = null
        var rebuildError: SyncError? = null
        var clearError: SyncError? = null
        val log = mutableListOf<LogRecord>()
        val service = PairingService(this, this, this, this, keyRecovery = this)
        override fun read() = config
        override fun verifyCredentialReferences(configuration: SyncConfiguration) {
            if (rebuilds > 0) throw SyncFailure(SyncError.CREDENTIAL_UNAVAILABLE)
        }
        override fun save(configuration: SyncConfiguration, password: CharArray, encryptionPassword: CharArray?) = kotlin.error("unexpected")
        override fun replaceEncryptionPassword(configuration: SyncConfiguration, password: CharArray) = kotlin.error("unexpected")
        override fun clear() { clearError?.let { throw SyncFailure(it) }; clears++; config = null }
        override fun getOrCreate() = device
        override fun open(endpoint: String, username: String, root: String, password: CharArray): ObjectProvider {
            remoteOpens++
            kotlin.error("unexpected")
        }
        override fun requireUsable() { keyError?.let { throw SyncFailure(it) } }
        override fun rebuildLostKey() {
            if (keyError != SyncError.CREDENTIAL_KEY_LOST) throw SyncFailure(SyncError.CREDENTIAL_UNAVAILABLE)
            rebuildError?.let { throw SyncFailure(it) }
            rebuilds++
            keyError = null
        }
        override fun log(record: LogRecord) { log += record }
    }
}
