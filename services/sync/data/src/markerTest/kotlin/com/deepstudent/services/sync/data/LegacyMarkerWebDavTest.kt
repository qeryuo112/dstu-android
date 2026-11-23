package com.deepstudent.services.sync.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.security.DsbkCrypto
import com.deepstudent.platform.security.DsbkFormat
import com.deepstudent.services.sync.api.PairingMode
import com.deepstudent.services.sync.api.PairingRequest
import com.deepstudent.services.sync.api.SyncConfiguration
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.api.SyncResult
import com.deepstudent.services.sync.backend.ConfigurationStore
import com.deepstudent.services.sync.backend.DeviceIdentity
import com.deepstudent.services.sync.backend.PairingService
import com.deepstudent.services.sync.backend.RepositoryEncryption
import com.deepstudent.services.sync.backend.SyncManifestCodec
import com.deepstudent.services.sync.backend.SyncWireCodec
import com.deepstudent.services.sync.data.transfer.DsbkSyncCryptography
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

open class LegacyMarkerWebDavTest {
    private val crypto = DsbkSyncCryptography(DsbkCrypto.production())
    private val encryption = RepositoryEncryption(crypto)
    private val password = "pw-生产-🔐"
    private val backup =
        hex(
            "4453424b01000001000300000004000000000102030405060708090a0b0c0d0e0f101112131415161718191a1b983c540dbfcf2f8d803bf47bcb88b3258ccd5b9518ec42586521c9b1956099d6a8e3a3412dd92b94ca4519",
        )

    @Test fun provesFrozenDesktopV1DsbkAndUpgradesMarkerInTheSameRepository() = runBlocking {
        LegacyMarkerWebDavFixture().use { repo ->
            repo.seedBackups("current" to backup)
            val local = Store()
            assertEquals(SyncResult.Failure(SyncError.ENCRYPTION_PASSWORD_INCORRECT), join(repo, local, "incorrect"))
            assertNull(local.config)
            assertTrue(repo.writes.isEmpty())
            assertArrayEquals(LegacyMarkerWebDavFixture.legacy, repo.marker)
            assertTrue(join(repo, local, password) is SyncResult.Success)
            assertEquals("same-instance", local.config?.instanceId)
            assertEquals("frozen-desktop-writer", marker(repo)["createdByDevice"].let { (it as JsonValue.Text).value })
            assertEquals(JsonValue.integer(2), marker(repo)["version"])
            assertEquals(1, repo.writes.count { it == PairingService.ENCRYPTION_MARKER_KEY })
            assertTrue(PairingService.ENCRYPTION_MARKER_KEY in repo.matchedWrites)
            assertTrue(repo.objects.keys.none { it.startsWith(".encryption-marker.") })
            assertArrayEquals(backup, repo.objects["backups/current.zip"])
            assertTrue(join(repo, Store(), password) is SyncResult.Success)
            assertEquals(1, repo.writes.count { it == PairingService.ENCRYPTION_MARKER_KEY })
        }
    }

    @Test fun v2DsbkProvesOnlyTheFirstChunkAndCanTryTheSecondNewestVersion(): Unit = runBlocking {
        val fixture = System.getenv("DSTU_S218_DESKTOP_FIXTURES")?.let(::File)
        val v2 = fixture?.resolve("backup-v2.dsbk")?.readBytes() ?: crypto.payload(password.toCharArray())
            .encode(ByteArray(DsbkFormat.STREAM_CHUNK + 41), blob = true)
        LegacyMarkerWebDavFixture().use { repo ->
            fixture?.resolve("desktop-marker-v1.json")?.let { repo.objects[PairingService.ENCRYPTION_MARKER_KEY] = it.readBytes() }
            repo.seedBackups("newest" to backup.copyOf().also { it[0] = 0 }, "second" to v2)
            val joined = join(repo, Store(), password)
            assertTrue("$joined after prefix reads ${repo.ranges}", joined is SyncResult.Success)
            val prefix = DsbkFormat.V2_HEADER_LEN + DsbkFormat.STREAM_CHUNK + DsbkFormat.GCM_TAG_LEN
            assertTrue(v2.size > prefix)
            assertEquals(
                listOf("backups/newest.zip" to 45, "backups/second.zip" to 45, "backups/second.zip" to prefix),
                repo.ranges.toList(),
            )
            fixture?.resolve("android-marker-v2.json")?.writeBytes(repo.marker)
        }
    }

    @Test fun wrongRangeCorruptArchiveAndPlaintextNeverStartTheClaim() = runBlocking {
        val cases = listOf(
            ("PK\u0003\u0004".toByteArray() + ByteArray(60)) to SyncError.ENCRYPTION_MODE_MISMATCH,
            backup.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() } to SyncError.ENCRYPTION_PASSWORD_INCORRECT,
            backup to SyncError.ENCRYPTED_DATA_INVALID,
        )
        for ((index, case) in cases.withIndex()) {
            LegacyMarkerWebDavFixture().use { repo ->
                val (bytes, error) = case
                repo.badRange = index == 2
                repo.seedBackups("latest" to bytes)
                val local = Store()
                assertEquals(SyncResult.Failure(error), join(repo, local, password))
                assertTrue(repo.writes.isEmpty())
                assertNull(local.config)
                assertArrayEquals(LegacyMarkerWebDavFixture.legacy, repo.marker)
            }
        }
    }

    @Test fun recordOnlyRepositoryRequiresAnAuthenticatedDeviceManifest() = runBlocking {
        LegacyMarkerWebDavFixture().use { repo ->
            val key = SyncWireCodec.manifestKey("owner")
            val body = SyncManifestCodec.encode(
                JsonValue.parseObject(
                    """{"sync_transaction_id":"t","databases":{},"status":"Complete","created_at":"2026-01-01T00:00:00Z","device_id":"owner"}""",
                ),
            )
            repo.objects[key] = body
            assertEquals(SyncResult.Failure(SyncError.ENCRYPTION_MODE_MISMATCH), join(repo, Store(), password))
            val sealed = crypto.payload(password.toCharArray()).encode(body)
            repo.objects[key] = sealed
            assertEquals(SyncResult.Failure(SyncError.ENCRYPTION_PASSWORD_INCORRECT), join(repo, Store(), "bad"))
            assertTrue(repo.writes.isEmpty())
            assertTrue(join(repo, Store(), password) is SyncResult.Success)
            assertArrayEquals(sealed, repo.objects[key])
        }
    }

    @Test fun strongTagPreservesForeignChangeBetweenReadAndPut() = runBlocking {
        LegacyMarkerWebDavFixture().use { repo ->
            val provider = repo.webdav
            val competitor = "foreign marker".toByteArray()
            repo.markerReadsBeforeTheft = 1
            repo.stealMarkerAfterConditionalRead = competitor
            assertFalse(
                provider.replaceClaimed(
                    PairingService.ENCRYPTION_MARKER_KEY,
                    LegacyMarkerWebDavFixture.legacy,
                    "our replacement".toByteArray(),
                ),
            )
            assertArrayEquals(competitor, repo.marker)
            assertEquals(listOf(PairingService.ENCRYPTION_MARKER_KEY), repo.matchedWrites.toList())
            assertTrue(repo.writes.isEmpty())
        }
    }

    private suspend fun join(repo: LegacyMarkerWebDavFixture, store: Store, password: String): SyncResult<SyncConfiguration> {
        val request = PairingRequest(repo.endpoint, "fixture", "repo", "fixture-webdav".toCharArray(), password.toCharArray())
        val result = PairingService(store, store, repo.factory, encryption = encryption).pair(PairingMode.JOIN, request)
        assertTrue(request.password.all { it == '\u0000' })
        assertTrue(request.encryptionPassword!!.all { it == '\u0000' })
        return result
    }

    private fun marker(repo: LegacyMarkerWebDavFixture) = JsonValue.parseObject(repo.marker.decodeToString())
    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private class Store :
        ConfigurationStore,
        DeviceIdentity {
        var config: SyncConfiguration? = null
        override fun getOrCreate() = "new-android"
        override fun read() = config
        override fun save(configuration: SyncConfiguration, password: CharArray, encryptionPassword: CharArray?) {
            config = configuration
        }
        override fun replaceEncryptionPassword(configuration: SyncConfiguration, password: CharArray) = error("not used")
        override fun clear() {
            config = null
        }
    }
}
