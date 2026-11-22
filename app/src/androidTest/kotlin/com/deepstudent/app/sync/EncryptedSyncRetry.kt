package com.deepstudent.app.sync

import android.util.Log
import com.deepstudent.core.common.id.Digests
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.api.SyncResult
import com.deepstudent.services.sync.api.SyncTransfer
import com.deepstudent.services.sync.backend.ObjectProvider
import com.deepstudent.services.sync.backend.PairingService
import com.deepstudent.services.sync.backend.ProviderFactory
import com.deepstudent.services.sync.backend.RepositoryEncryption
import com.deepstudent.services.sync.backend.SyncFailure
import com.deepstudent.services.sync.backend.SyncWireCodec
import com.deepstudent.services.sync.backend.transfer.PendingPublication
import com.deepstudent.services.sync.data.LocalSyncStore
import com.deepstudent.services.sync.data.transfer.SqlUploadState
import com.deepstudent.services.sync.data.transfer.StoredWebDavProvider
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import org.junit.Assert.*

/** Three separate instrumentations let the HTTP fixture observe the wrong-password boundary. */
internal class EncryptedSyncRetry(
    private val db: SqlDatabase,
    private val store: LocalSyncStore,
    private val providers: ProviderFactory,
    private val encryption: RepositoryEncryption,
    private val witness: File,
    private val desktopDevice: String,
) {
    private val config = requireNotNull(store.read())
    private val journal = SqlUploadState(db, config.instanceId, config.deviceId)

    suspend fun reserve(transfer: (ProviderFactory) -> SyncTransfer) {
        val before = journal.published()
        val writes = ObservedProvider(providers, failAfterChangePut = true)
        assertEquals(SyncResult.Failure(SyncError.NETWORK), transfer(writes).synchronize())
        val pending = requireNotNull(journal.pending())
        assertEquals(before + 1, pending.sequence)
        assertEquals(before, journal.published())
        assertTrue(pendingIds().containsAll(pending.changeLogIds))
        assertTrue(pending.changeLogIds.isNotEmpty())
        assertEquals("DSBK", pending.payload.decodeToString(0, 4))
        assertEquals(1, writes.changeWrites)
    }

    suspend fun wrongPassword(transfer: (ProviderFactory) -> SyncTransfer, nextDesktopSequence: Long) {
        val before = capture()
        assertEquals(nextDesktopSequence - 1, desktopCursor(before))
        assertTrue(before.pending.changeLogIds.isNotEmpty())
        val writes = ObservedProvider(providers)
        val password = "incorrect stored fixture".toCharArray()
        try { store.replaceEncryptionPassword(config, password) } finally { password.fill('\u0000') }
        assertEquals(SyncResult.Failure(SyncError.ENCRYPTION_PASSWORD_INCORRECT), transfer(writes).synchronize())
        assertEquals(0, writes.mutations)
        val after = capture()
        assertPublication(before.pending, after.pending)
        assertEquals(before.published, after.published)
        assertEquals(before.changeLog, after.changeLog)
        assertEquals(before.cursors, after.cursors)
        // This witness contains only a digest, never passwords or plaintext, and crosses process boundaries.
        witness.writeText(fingerprint(before), Charsets.US_ASCII)
        Log.i("DstuSyncTest", "encrypted wrong-password boundary verified")
    }

    suspend fun correctPassword(transfer: (ProviderFactory) -> SyncTransfer, nextDesktopSequence: Long) {
        val before = capture()
        assertTrue("wrong-password witness is required", witness.isFile)
        assertEquals(witness.readText(Charsets.US_ASCII), fingerprint(before))
        assertEquals(nextDesktopSequence - 1, desktopCursor(before))
        val pairing = PairingService(store, store, providers, encryption = encryption, openStored = StoredWebDavProvider(store, providers)::open)
        val correction = publicEncryptionPassword()
        assertEquals(SyncResult.Success(Unit), pairing.updateEncryptionPassword(correction))
        assertTrue(correction.all { it == '\u0000' })
        assertEquals(config, store.read())
        val writes = ObservedProvider(providers)
        assertEquals(SyncResult.Success(Unit), transfer(writes).synchronize())
        // The prior PUT succeeded remotely despite a simulated lost response: never encrypt or PUT again.
        assertEquals(0, writes.changeWrites)
        assertEquals(before.pending.sequence, journal.published())
        assertNull(journal.pending())
        val changeLog = changeLog().toMap()
        before.pending.changeLogIds.forEach { assertEquals(before.pending.version, requireNotNull(changeLog[it])) }
        assertEquals(nextDesktopSequence, desktopCursor(cursors()))
        assertTrue(witness.delete())
        Log.i("DstuSyncTest", "encrypted same-batch retry verified")
    }

    private data class Cursor(val instance: String, val device: String, val sequence: Long)
    private data class Snapshot(val pending: PendingPublication, val published: Long,
        val changeLog: List<Pair<Long, Long>>, val cursors: List<Cursor>)

    private fun capture() = Snapshot(requireNotNull(journal.pending()), journal.published(), changeLog(), cursors())
    private fun desktopCursor(snapshot: Snapshot) = desktopCursor(snapshot.cursors)
    private fun desktopCursor(cursors: List<Cursor>): Long = cursors.single {
        it.instance == config.instanceId && it.device == SyncWireCodec.deviceHash(desktopDevice)
    }.sequence

    private fun fingerprint(snapshot: Snapshot): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            val pending = snapshot.pending
            out.writeUTF(pending.key)
            out.writeLong(pending.sequence)
            out.writeLong(pending.version)
            out.writeInt(pending.payload.size)
            out.write(pending.payload)
            out.writeInt(pending.changeLogIds.size)
            pending.changeLogIds.forEach(out::writeLong)
            out.writeLong(snapshot.published)
            out.writeInt(snapshot.changeLog.size)
            snapshot.changeLog.forEach { (id, version) -> out.writeLong(id); out.writeLong(version) }
            out.writeInt(snapshot.cursors.size)
            snapshot.cursors.forEach { cursor -> out.writeUTF(cursor.instance); out.writeUTF(cursor.device); out.writeLong(cursor.sequence) }
        }
        return Digests.sha256Hex(bytes.toByteArray())
    }

    private fun assertPublication(expected: PendingPublication, actual: PendingPublication) {
        assertEquals(expected.key, actual.key)
        assertEquals(expected.sequence, actual.sequence)
        assertEquals(expected.version, actual.version)
        assertEquals(expected.changeLogIds, actual.changeLogIds)
        assertArrayEquals(expected.payload, actual.payload)
    }

    private fun changeLog() = db.query("SELECT id,COALESCE(sync_version,0) FROM __change_log ORDER BY id") { it.long(0) to it.long(1) }
    private fun pendingIds() = changeLog().filter { it.second == 0L }.map { it.first }
    private fun cursors() = db.query("SELECT instance,device,seq FROM __android_sync_cursor_v2 ORDER BY instance,device") {
        Cursor(it.text(0), it.text(1), it.long(2))
    }
}

private class ObservedProvider(private val factory: ProviderFactory, private val failAfterChangePut: Boolean = false) : ProviderFactory {
    var mutations = 0
        private set
    var changeWrites = 0
        private set

    override fun open(endpoint: String, username: String, root: String, password: CharArray): ObjectProvider {
        val provider = factory.open(endpoint, username, root, password)
        return object : ObjectProvider by provider {
            override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) {
                mutations++
                provider.put(key, value, ifAbsent)
                if (key.startsWith("data_governance/changes/") || key.startsWith("data_governance/v4/shards/")) {
                    changeWrites++
                    if (failAfterChangePut) throw SyncFailure(SyncError.NETWORK)
                }
            }
            override suspend fun delete(key: String) {
                mutations++
                provider.delete(key)
            }
        }
    }
}
