package com.deepstudent.app.diagnostics

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.backup.BackupForegroundRule
import com.deepstudent.app.storage.AndroidOperationLogger
import com.deepstudent.platform.security.AndroidKeystoreKeyWrapper
import com.deepstudent.platform.security.SecurityFactory
import com.deepstudent.services.sync.api.PairingMode
import com.deepstudent.services.sync.api.PairingRequest
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.api.SyncResult
import com.deepstudent.services.sync.backend.PairingService
import com.deepstudent.services.sync.data.LocalSyncStore
import com.deepstudent.services.sync.data.WebDavProviderFactory
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class S231RealFailureDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule(order = 0)
    internal val owned = S231IsolatedAppRule()

    @get:Rule(order = 1)
    val foreground = BackupForegroundRule()

    @get:Rule(order = 2)
    val temp = TemporaryFolder(context.cacheDir.canonicalFile)

    @Test
    fun rejectedRealWebDavAuthenticationIsCorrelatedAndNotDisclosedByShare(): Unit = runBlocking {
        val alias = "fixture.s231.${UUID.randomUUID()}"
        val identity = "synthetic-s231-username"
        val password = "synthetic-s231-password".toCharArray()
        try {
            SecurityFactory.create(temp.root.resolve("security"), alias).use { security ->
                val store = LocalSyncStore(temp.root.resolve("sync"), security.credentials)
                val providers = WebDavProviderFactory(allowLoopbackHttp = true)
                val pairing = PairingService(store, store, providers, AndroidOperationLogger)
                MockWebServer().use { remote ->
                    remote.enqueue(MockResponse().setResponseCode(401))
                    val endpoint = remote.url("/").toString()
                    val request = PairingRequest(endpoint, identity, "public-fixture", password)
                    assertEquals(SyncResult.Failure(SyncError.AUTHENTICATION), pairing.pair(PairingMode.JOIN, request))
                    assertEquals("GET", remote.takeRequest(5, TimeUnit.SECONDS)?.method)
                    assertNull(store.read())
                    assertTrue(password.all { it == '\u0000' })
                    verifyActualExport(identity, endpoint)
                }
            }
        } finally {
            password.fill('\u0000')
            AndroidKeystoreKeyWrapper(alias).deleteKey()
        }
    }

    private fun verifyActualExport(identity: String, endpoint: String) {
        val snapshot = S231Diagnostics.buffer.snapshot()
        val error = snapshot.entries.last { it.error == S231ErrorCode.AUTHENTICATION }
        assertEquals(S231Event.SYNC_CONFIGURATION_FINISHED, error.event)
        assertTrue(error.correlation != null)
        assertTrue(snapshot.entries.any { it.event == S231Event.SYNC_CONFIGURATION_STARTED && it.correlation == error.correlation })
        val send = S231Share.intent(context, snapshot)
        val uri = requireNotNull(send.clipData).getItemAt(0).uri
        val report = requireNotNull(context.contentResolver.openInputStream(uri)).bufferedReader().use { it.readText() }
        assertTrue(report.contains("error=AUTHENTICATION"))
        for (canary in listOf(identity, "synthetic-s231-password", endpoint, "public-fixture")) {
            assertFalse("share included synthetic private input", report.contains(canary))
        }
    }
}
