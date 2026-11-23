package com.deepstudent.app.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deepstudent.app.backup.BackupForegroundRule
import com.deepstudent.app.backup.remediationCorpus
import com.deepstudent.app.backup.remediationTemporaryFolder
import com.deepstudent.app.storage.AndroidOperationLogger
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.security.AndroidKeystoreKeyWrapper
import com.deepstudent.platform.security.DsbkFormat
import com.deepstudent.platform.security.SecurityFactory
import com.deepstudent.platform.security.SecurityPorts
import com.deepstudent.services.sync.api.PairingMode
import com.deepstudent.services.sync.api.PairingRequest
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.api.SyncResult
import com.deepstudent.services.sync.backend.PairingService
import com.deepstudent.services.sync.backend.RepositoryEncryption
import com.deepstudent.services.sync.data.LegacyMarkerWebDavFixture
import com.deepstudent.services.sync.data.LocalSyncStore
import com.deepstudent.services.sync.data.transfer.DsbkSyncCryptography
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DesktopLegacyMarkerDeviceTest {
    @get:Rule val foreground = BackupForegroundRule()

    @get:Rule val temp = remediationTemporaryFolder()
    private val alias = "fixture.s218.${UUID.randomUUID()}"
    private val password = "pw-生产-🔐"
    private val output get() = remediationCorpus().resolve("android-marker-v2.json")
    private val markerKey = PairingService.ENCRYPTION_MARKER_KEY
    private val leaseKey = "$markerKey.lease"
    private val pendingPrefix = "$markerKey.pending/"

    @After fun deleteFixtureKey() = AndroidKeystoreKeyWrapper(alias).deleteKey()

    @Test fun desktopCiphertextUpgradesTheSameRepositoryAndPersistsAndroidPairing(): Unit = runBlocking {
        desktopRepository().use { repo ->
            val original = repo.objects.mapValues { it.value.copyOf() }
            openSecurity().use { security ->
                val local = store(security)
                assertEquals(SyncResult.Failure(SyncError.ENCRYPTION_PASSWORD_INCORRECT), join(repo, security, local, "wrong"))
                assertNull(local.read())
                assertUnchanged(repo, original)
                repo.ranges.clear()
                val joined = join(repo, security, local) as SyncResult.Success
                assertTrue(joined.value.encrypted)
                assertEquals("same-instance", joined.value.instanceId)
                val prefix = DsbkFormat.V2_HEADER_LEN + DsbkFormat.STREAM_CHUNK + DsbkFormat.GCM_TAG_LEN
                assertEquals(listOf("backups/latest.zip" to 45, "backups/latest.zip" to prefix), repo.ranges.toList())
                assertEquals(JsonValue.integer(2), JsonValue.parseObject(repo.marker.decodeToString())["version"])
                assertEquals(1, repo.writes.count { it == markerKey })
                assertTrue(repo.matchedWrites.contains(markerKey))
                assertEquals(original.keys, repo.objects.keys)
                original.filterKeys { it != markerKey }.forEach { (key, value) -> assertArrayEquals(value, repo.objects[key]) }
                output.writeBytes(repo.marker)
                assertEquals(joined.value, local.read())
            }
            openSecurity().use { security ->
                val cold = store(security)
                val configuration = checkNotNull(cold.read())
                cold.verifyCredentialReferences(configuration)
                val wrapped = cold.encryptionPassword(configuration)
                try {
                    wrapped.use { assertEquals(password, it.toString(Charsets.UTF_8)) }
                } finally {
                    wrapped.close()
                }
                assertTrue(join(repo, security, store(security, "other-writer")) is SyncResult.Success)
                assertEquals(1, repo.writes.count { it == markerKey })
            }
        }
    }

    @Test fun malformedMarkerRefusesToWriteEvenWithValidBackupPassword(): Unit = runBlocking {
        desktopRepository().use { repo ->
            repo.objects[markerKey] = "{\"version\":1,\"createdAt\":\"invalid\"}".toByteArray()
            val original = repo.objects.mapValues { it.value.copyOf() }
            openSecurity().use { security ->
                val local = store(security)
                assertEquals(SyncResult.Failure(SyncError.INVALID_ENCRYPTION_MARKER), join(repo, security, local))
                assertNull(local.read())
                assertUnchanged(repo, original)
            }
        }
    }

    @Test fun competingMarkerAtConditionalPutIsNeverOverwritten(): Unit = runBlocking {
        desktopRepository().use { repo ->
            val winner = repo.marker.decodeToString().replace("frozen-desktop-writer", "other-writer").toByteArray()
            repo.competitorAtMarkerWrite = winner
            openSecurity().use { security ->
                val local = store(security)
                assertEquals(SyncResult.Failure(SyncError.ENCRYPTION_CLAIM_CONFLICT), join(repo, security, local))
                assertNull(local.read())
                assertTrue(repo.matchedWrites.contains(markerKey))
                assertArrayEquals(winner, repo.marker)
                assertFalse(repo.writes.contains(markerKey))
                assertFalse(repo.objects.keys.any { it.startsWith("$markerKey.") })
            }
        }
    }

    @Test fun cancellationAfterLeaseBeforeCommitKeepsOldMarkerAndNoPairing(): Unit = runBlocking {
        desktopRepository().use { repo ->
            val original = repo.objects.mapValues { it.value.copyOf() }
            val leaseWritten = CompletableDeferred<Unit>()
            repo.afterLeaseWrite = { leaseWritten.complete(Unit) }
            openSecurity().use { security ->
                val local = store(security)
                val pairing = async { join(repo, security, local) }
                withTimeout(30_000) { leaseWritten.await() }
                pairing.cancelAndJoin()
                assertTrue(pairing.isCancelled)
                assertNull(local.read())
                assertUnchanged(repo, original, noWrites = false)
                assertTrue(repo.writes.contains(leaseKey))
                assertFalse(repo.writes.contains(markerKey))
            }
        }
    }

    @Test fun lostReadbackPreservesCommittedMarkerAndClaimButDoesNotSavePairing(): Unit = runBlocking {
        desktopRepository().use { repo ->
            repo.failMarkerReadback = true
            openSecurity().use { security ->
                val local = store(security)
                assertEquals(SyncResult.Failure(SyncError.ENCRYPTION_UPGRADE_UNCERTAIN), join(repo, security, local))
                assertNull(local.read())
                val upgraded = repo.marker.copyOf()
                assertEquals(JsonValue.integer(2), JsonValue.parseObject(upgraded.decodeToString())["version"])
                assertTrue(repo.objects.containsKey(leaseKey))
                assertEquals(1, repo.objects.keys.count { it.startsWith(pendingPrefix) })
                repo.failMarkerReadback = false
                assertTrue(join(repo, security, local) is SyncResult.Success)
                assertArrayEquals(upgraded, repo.marker)
                assertEquals(1, repo.writes.count { it == markerKey })
            }
        }
    }

    private fun openSecurity() = SecurityFactory.create(temp.root.canonicalFile.resolve("keystore"), alias)
    private fun store(security: SecurityPorts, name: String = "local") =
        LocalSyncStore(temp.root.canonicalFile.resolve(name), security.credentials)

    private fun desktopRepository(): LegacyMarkerWebDavFixture {
        val corpus = remediationCorpus()
        val marker = corpus.resolve("desktop-marker-v1.json").readBytes()
        val backup = corpus.resolve("backup-v2.dsbk").readBytes()
        return LegacyMarkerWebDavFixture().also { repo ->
            repo.objects[markerKey] = marker
            repo.seedBackups("latest" to backup)
        }
    }

    private suspend fun join(repo: LegacyMarkerWebDavFixture, security: SecurityPorts, local: LocalSyncStore, pass: String = password) =
        PairingService(
            local,
            local,
            repo.factory,
            AndroidOperationLogger,
            encryption = RepositoryEncryption(DsbkSyncCryptography(security.dsbk)),
        ).pair(
            PairingMode.JOIN,
            PairingRequest(repo.endpoint, "fixture", "repo", "fixture-webdav".toCharArray(), pass.toCharArray()),
        )

    private fun assertUnchanged(repo: LegacyMarkerWebDavFixture, original: Map<String, ByteArray>, noWrites: Boolean = true) {
        if (noWrites) assertTrue(repo.writes.isEmpty())
        assertEquals(original.keys, repo.objects.keys)
        original.forEach { (key, value) -> assertArrayEquals(value, repo.objects[key]) }
    }
}
