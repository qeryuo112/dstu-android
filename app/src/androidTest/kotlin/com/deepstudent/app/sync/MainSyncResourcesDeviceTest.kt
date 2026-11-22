package com.deepstudent.app.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.editor.EditorTestHost
import com.deepstudent.app.security.MainSecurityResources
import com.deepstudent.features.sync.ui.SyncUiState
import com.deepstudent.platform.security.AndroidKeystoreKeyWrapper
import com.deepstudent.platform.security.SecurityFactory
import com.deepstudent.platform.security.SecurityPorts
import com.deepstudent.services.sync.api.PairingMode
import com.deepstudent.services.sync.api.PairingRequest
import com.deepstudent.services.sync.api.SyncConfiguration
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.PairingService
import com.deepstudent.services.sync.data.LocalSyncStore
import java.io.File
import java.util.UUID
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainSyncResourcesDeviceTest {
    private val id = UUID.randomUUID().toString()
    private val alias = "com.deepstudent.app.sync.test.$id"
    private val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile, "sync-test-$id")
    private val foreground = EditorTestHost()

    @Before fun start() {
        foreground.start()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            foreground.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @After fun cleanup() {
        try {
            check(alias != AndroidKeystoreKeyWrapper.DEFAULT_ALIAS)
            AndroidKeystoreKeyWrapper(alias).deleteKey()
            root.deleteRecursively()
        } finally { foreground.close() }
    }

    @Test fun realFactoryReopensIsolatedConfigurationAndCredential() = runBlocking {
        lateinit var config: SyncConfiguration
        openSecurity().use { security ->
            val store = LocalSyncStore(File(root, "sync"), security.credentials)
            config = SyncConfiguration("https://example.invalid", "user", "root", store.getOrCreate(), "instance")
            store.save(config, "test-secret".toCharArray())
            MainSyncResources.open(File(root, "sync"), security).use { resources ->
                assertEquals(SyncUiState.Connected(config), awaitState(resources) { it is SyncUiState.Connected })
            }
        }
        openSecurity().use { security ->
            MainSyncResources.open(File(root, "sync"), security).use { resources ->
                assertEquals(SyncUiState.Connected(config), awaitState(resources) { it is SyncUiState.Connected })
                val password = "secret".toCharArray()
                resources.controller.pair(PairingMode.JOIN, PairingRequest("not a url", "user", "root", password))
                assertEquals(SyncUiState.Error(SyncError.INVALID_CONFIGURATION), awaitState(resources) { it is SyncUiState.Error })
                assertTrue(password.all { it == '\u0000' })
            }
        }
    }

    @Test fun realFactoryPerformsHttpJoin(): Unit = runBlocking { exerciseHttpJoin(recover = false) }

    @Test fun lostIsolatedKeystoreAliasCanRejoinWithoutChangingDeviceOrLocalData(): Unit = runBlocking {
        exerciseHttpJoin(recover = true)
    }

    private suspend fun exerciseHttpJoin(recover: Boolean) {
        ServerSocket(0, 3, InetAddress.getByName("127.0.0.1")).use { server ->
            val observed = CopyOnWriteArrayList<String>()
            val marker = "/${PairingService.ENCRYPTION_MARKER_KEY}"
            val format = "/${PairingService.FORMAT_KEY}"
            val instance = "/${PairingService.INSTANCE_KEY}"
            val worker = thread(isDaemon = true) {
                try { repeat(4) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader()
                        val path = reader.readLine()?.split(' ')?.getOrNull(1).orEmpty()
                        observed += path
                        while (!reader.readLine().isNullOrEmpty()) Unit
                        val body = when (path) {
                            format -> "{\"format_version\":4,\"min_client\":3}"
                            instance -> "{\"instance_id\":\"fixture-instance\"}"
                            else -> ""
                        }
                        val status = if (body.isEmpty()) "404 Not Found" else "200 OK"
                        val bytes = body.toByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 $status\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                } } catch (_: java.io.IOException) { }
            }
            try {
                openSecurity().use { security ->
                    val endpoint = "http://127.0.0.1:${server.localPort}"
                    val old = if (recover) seedLostKey(security, endpoint) else null
                    MainSyncResources.open(File(root, "sync"), security, allowLoopbackHttp = true).use { resources ->
                        if (old != null) confirmRecovery(resources, old, observed)
                        awaitState(resources) { it == SyncUiState.Unconfigured }
                        resources.controller.pair(PairingMode.JOIN, PairingRequest(
                            endpoint, "user", "", "secret".toCharArray(),
                        ))
                        val connected = awaitState(resources) { it is SyncUiState.Connected || it is SyncUiState.Error } as SyncUiState.Connected
                        assertEquals("fixture-instance", connected.configuration.instanceId)
                        old?.let {
                            assertEquals(it.device, connected.configuration.deviceId)
                            assertEquals("local fixture", it.localData.readText())
                            assertEquals("pending fixture", it.pending.readText())
                            assertTrue(hasAlias())
                            assertEquals(connected.configuration, LocalSyncStore(File(root, "sync"), security.credentials).read())
                        }
                        // JOIN checks the marker again before saving state; model all four requests.
                        assertEquals(listOf(marker, format, instance, marker), observed.toList())
                    }
                }
            } finally {
                server.close()
                worker.join(5_000)
            }
        }
    }

    private data class RecoveryFixture(val device: String, val localData: File, val pending: File, val binding: ByteArray)

    private fun seedLostKey(security: SecurityPorts, endpoint: String): RecoveryFixture {
        val store = LocalSyncStore(File(root, "sync"), security.credentials)
        val device = store.getOrCreate()
        store.save(SyncConfiguration(endpoint, "user", "", device, "fixture-instance"), "old fixture".toCharArray())
        val local = File(root, "active/note.txt").apply { parentFile!!.mkdirs(); writeText("local fixture") }
        val pending = File(root, "sync/pending-fixture").apply { writeText("pending fixture") }
        val binding = File(root, "sync/configuration.json").readBytes()
        assertTrue(hasAlias())
        AndroidKeystoreKeyWrapper(alias).deleteKey()
        return RecoveryFixture(device, local, pending, binding)
    }

    private suspend fun confirmRecovery(resources: MainSyncResources, old: RecoveryFixture, observed: List<String>) {
        awaitState(resources) { it is SyncUiState.KeyRecovery }
        val path = File(root, "sync/configuration.json")
        assertArrayEquals(old.binding, path.readBytes())
        assertFalse(hasAlias())
        assertTrue(observed.isEmpty())
        resources.controller.resetCredentialsAfterKeyLoss()
        awaitState(resources) { it == SyncUiState.Unconfigured }
        assertTrue(hasAlias())
        assertFalse(path.exists())
        assertEquals(old.device, File(root, "sync/device-id").readText())
        assertTrue(observed.isEmpty())
    }

    private fun hasAlias(): Boolean = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias)

    private fun openSecurity(): SecurityPorts =
        MainSecurityResources.open(InstrumentationRegistry.getInstrumentation().targetContext) { _, audit ->
            SecurityFactory.create(File(root, "security"), alias, audit = audit)
        }

    private suspend fun awaitState(resources: MainSyncResources, predicate: (SyncUiState) -> Boolean): SyncUiState =
        withTimeout(10_000) {
            while (!predicate(resources.controller.state.value)) delay(10)
            resources.controller.state.value
        }
}
