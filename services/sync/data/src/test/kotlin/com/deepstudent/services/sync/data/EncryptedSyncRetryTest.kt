package com.deepstudent.services.sync.data

import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.security.DsbkCrypto
import com.deepstudent.services.sync.api.DomainSyncAdapter
import com.deepstudent.services.sync.api.PairingMode
import com.deepstudent.services.sync.api.PairingRequest
import com.deepstudent.services.sync.api.SyncConfiguration
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.api.SyncResult
import com.deepstudent.services.sync.api.SyncTransferPhase
import com.deepstudent.services.sync.backend.ConfigurationStore
import com.deepstudent.services.sync.backend.DeviceIdentity
import com.deepstudent.services.sync.backend.ObjectProvider
import com.deepstudent.services.sync.backend.PairingService
import com.deepstudent.services.sync.backend.ProviderFactory
import com.deepstudent.services.sync.backend.RepositoryEncryption
import com.deepstudent.services.sync.backend.SyncFailure
import com.deepstudent.services.sync.backend.SyncPayloadCodec
import com.deepstudent.services.sync.backend.SyncWireCodec
import com.deepstudent.services.sync.backend.transfer.BlobTransfer
import com.deepstudent.services.sync.backend.transfer.ChangeTransfer
import com.deepstudent.services.sync.backend.transfer.PublicationJournal
import com.deepstudent.services.sync.backend.transfer.RemoteGate
import com.deepstudent.services.sync.backend.transfer.SyncBlob
import com.deepstudent.services.sync.backend.transfer.SyncBlobs
import com.deepstudent.services.sync.backend.transfer.SyncCompression
import com.deepstudent.services.sync.backend.transfer.SyncPipeline
import com.deepstudent.services.sync.backend.transfer.SyncUploadState
import com.deepstudent.services.sync.data.transfer.DsbkSyncCryptography
import com.deepstudent.services.sync.data.transfer.SqlUploadState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Real DSBK and SQLite publication journal, with in-memory network and domain ports. */
class EncryptedSyncRetryTest {
    private val root = Files.createTempDirectory("encrypted-sync-jvm-").toFile()
    private val crypto = DsbkSyncCryptography(DsbkCrypto.production())
    private val encryption = RepositoryEncryption(crypto)
    private val password = "public integration password".toCharArray()
    private val compression = object : SyncCompression {
        override fun encode(bytes: ByteArray) = bytes
        override fun decode(bytes: ByteArray) = bytes
    }

    @After fun cleanup() {
        password.fill('\u0000')
        root.deleteRecursively()
    }

    @Test fun failedAuthenticationAndConflictingReservedObjectPreserveCheckpointsUntilExactRetry() = runBlocking {
        val remote = Provider()
        val config = createRepository(remote)
        val codec = crypto.payload(password)
        val peer = Domain(2, "peer-note")
        ChangeTransfer(remote, compression, VolatileState(), peer, PEER, codec).upload(3)
        val peerKey = changeKeys(remote).single()
        val peerWire = remote.objects.getValue(peerKey).copyOf()
        val local = Domain(17, "local-note")
        lateinit var reservedKey: String
        lateinit var reservedWire: ByteArray
        DatabaseFactory.openLocal(File(root, "journal.db")).use { db ->
            val journal = SqlUploadState(db, config.instanceId, LOCAL)
            // The server accepted the immutable object but the response was lost.
            remote.loseNextPutResponse = true
            val error = assertThrows(SyncFailure::class.java) {
                runBlocking { ChangeTransfer(remote, compression, journal, local, LOCAL, codec).upload(3) }
            }
            assertEquals(SyncError.NETWORK, error.error)
            val pending = journal.pending()!!
            reservedKey = pending.key
            reservedWire = pending.payload.copyOf()
            assertEquals(1, pending.sequence)
            assertEquals(listOf(17L), pending.receipts.map { it.changeLogId })
            assertArrayEquals(reservedWire, remote.objects.getValue(reservedKey))
            assertEquals(0, journal.published())
            assertFalse(local.marked)
        }
        val beforeAuth = remote.snapshot()
        val putCount = remote.puts.size
        DatabaseFactory.openLocal(File(root, "journal.db")).use { db ->
            val journal = SqlUploadState(db, config.instanceId, LOCAL)
            val wrong = assertThrows(SyncFailure::class.java) {
                runBlocking { encryption.check(remote, true, "wrong password".toCharArray()) }
            }
            assertEquals(SyncError.ENCRYPTION_PASSWORD_INCORRECT, wrong.error)
            assertSafePoint(journal, local, reservedKey, reservedWire)
            remote.assertSnapshot(beforeAuth)
            assertEquals(putCount, remote.puts.size)

            val marker = remote.objects.getValue(PairingService.ENCRYPTION_MARKER_KEY).copyOf()
            remote.objects[PairingService.ENCRYPTION_MARKER_KEY] = "{}".toByteArray() // external tampering
            val badMarker = remote.snapshot()
            val invalid = assertThrows(SyncFailure::class.java) { runBlocking { encryption.check(remote, true, password) } }
            assertEquals(SyncError.INVALID_ENCRYPTION_MARKER, invalid.error)
            assertSafePoint(journal, local, reservedKey, reservedWire)
            remote.assertSnapshot(badMarker)
            assertEquals(putCount, remote.puts.size)
            remote.objects[PairingService.ENCRYPTION_MARKER_KEY] = marker

            // A conflicting remote object must not be overwritten or acknowledged.
            remote.objects[reservedKey] = reservedWire.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            val conflict = remote.snapshot()
            val blocked = pipeline(remote, config, journal, local, codec)
            assertEquals(SyncResult.Failure(SyncError.INVALID_REPOSITORY), blocked.synchronize())
            assertEquals(SyncTransferPhase.FAILED, blocked.state.value.phase)
            assertSafePoint(journal, local, reservedKey, reservedWire)
            remote.assertSnapshot(conflict)
            assertEquals(putCount, remote.puts.size)

            // External repair restores the exact accepted bytes, not a fresh encryption.
            remote.objects[reservedKey] = reservedWire.copyOf()
            val retry = pipeline(remote, config, journal, local, codec)
            assertEquals(SyncResult.Success(Unit), retry.synchronize())
            assertEquals(SyncTransferPhase.COMPLETE, retry.state.value.phase)
            assertEquals(1, journal.published())
            assertNull(journal.pending())
            assertTrue(local.marked)
            assertEquals(listOf(17L), local.markedIds)
            assertEquals(1, local.cursor(SyncWireCodec.deviceHash(PEER)))
            assertEquals(1, local.applied.size)
            assertTrue(local.applied.single().decodeToString().contains("peer-note"))
            assertArrayEquals(peerWire, remote.objects.getValue(peerKey))
            assertArrayEquals(reservedWire, remote.objects.getValue(reservedKey))
            assertEquals(putCount, remote.puts.size)
            assertEquals(setOf(peerKey, reservedKey), changeKeys(remote).toSet())

            assertEquals(SyncResult.Success(Unit), retry.synchronize())
            assertEquals(1, local.applied.size)
            assertEquals(1, local.markCount)
            assertEquals(putCount, remote.puts.size)
        }
        DatabaseFactory.openLocal(File(root, "journal.db")).use { db ->
            val reopened = SqlUploadState(db, config.instanceId, LOCAL)
            assertEquals(1, reopened.published())
            assertNull(reopened.pending())
        }
    }

    @Test fun corruptedIncomingBatchDoesNotAdvanceCursorOrPublishLocalChanges() = runBlocking {
        val remote = Provider()
        val config = createRepository(remote)
        val codec = crypto.payload(password)
        ChangeTransfer(remote, compression, VolatileState(), Domain(2, "peer-note"), PEER, codec).upload(3)
        val peerKey = changeKeys(remote).single()
        val intact = remote.objects.getValue(peerKey).copyOf()
        remote.objects[peerKey] = intact.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val corrupted = remote.snapshot()
        val putCount = remote.puts.size
        val local = Domain(17, "local-note")
        DatabaseFactory.openLocal(File(root, "incoming.db")).use { db ->
            val journal = SqlUploadState(db, config.instanceId, LOCAL)
            val transfer = pipeline(remote, config, journal, local, codec)
            assertEquals(SyncResult.Failure(SyncError.ENCRYPTED_DATA_INVALID), transfer.synchronize())
            assertEquals(SyncTransferPhase.FAILED, transfer.state.value.phase)
            assertEquals(0, local.cursor(SyncWireCodec.deviceHash(PEER)))
            assertTrue(local.applied.isEmpty())
            assertFalse(local.marked)
            assertEquals(0, journal.published())
            assertNull(journal.pending())
            assertEquals(putCount, remote.puts.size)
            remote.assertSnapshot(corrupted)

            remote.objects[peerKey] = intact
            assertEquals(SyncResult.Success(Unit), transfer.synchronize())
            assertEquals(1, local.cursor(SyncWireCodec.deviceHash(PEER)))
            assertEquals(1, local.applied.size)
            assertEquals(1, journal.published())
            assertNull(journal.pending())
            assertEquals(listOf(17L), local.markedIds)
            assertArrayEquals(intact, remote.objects.getValue(peerKey))
            val localKey = changeKeys(remote).single { it != peerKey }
            assertTrue(codec.decode(remote.objects.getValue(localKey)).decodeToString().contains("local-note"))
            assertEquals(putCount + 1, remote.puts.size)
        }
    }

    @Test fun encryptedBlobRetryReusesExistingCiphertextAndTamperNeverInstalls() = runBlocking {
        val remote = Provider()
        val codec = crypto.payload(password)
        val bytes = "public blob with unchanged hash".toByteArray()
        val hash = Digests.sha256Hex(bytes)
        val blob = SyncBlob(hash, "${hash.take(2)}/$hash.bin", bytes.size.toLong(), "2026-11-18T00:00:00Z")
        val key = "data_governance/blobs/${blob.relativePath}"
        val valid = codec.encode(bytes, blob = true)
        remote.objects[key] = valid.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val badSnapshot = remote.snapshot()
        val local = Blobs(blob, bytes)
        val failure = assertThrows(SyncFailure::class.java) { runBlocking { BlobTransfer(remote, local, codec).synchronize() } }
        assertEquals(SyncError.ENCRYPTED_DATA_INVALID, failure.error)
        assertTrue(remote.puts.isEmpty())
        remote.assertSnapshot(badSnapshot)

        remote.objects[key] = valid.copyOf() // another actor repairs the immutable object
        BlobTransfer(remote, local, codec).synchronize()
        assertArrayEquals(valid, remote.objects.getValue(key))
        assertFalse(remote.puts.contains(key))
        val manifestKey = remote.puts.single()
        assertTrue(manifestKey.startsWith("data_governance/file_manifests/blobs/"))
        val manifest = JsonValue.parseObject(codec.decode(remote.objects.getValue(manifestKey)).decodeToString())
        val entries = manifest["entries"] as JsonValue.Object
        val entry = entries[hash] as JsonValue.Object
        assertEquals(Digests.sha256Hex(valid), (entry["cipher_sha256"] as JsonValue.Text).value)
        assertEquals(valid.size.toLong(), (entry["cipher_size"] as JsonValue.Number).asLongOrNull())

        val receiver = Blobs()
        remote.objects[key] = valid.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val tampered = remote.snapshot()
        val badDownload = assertThrows(SyncFailure::class.java) { runBlocking { BlobTransfer(remote, receiver, codec).synchronize() } }
        assertEquals(SyncError.ENCRYPTED_DATA_INVALID, badDownload.error)
        assertEquals(0, receiver.installs)
        assertEquals(1, remote.puts.size)
        remote.assertSnapshot(tampered)
        // Even if metadata agrees with the damaged wire hash, AEAD must reject it before install.
        val validManifest = remote.objects.getValue(manifestKey).copyOf()
        val damagedWire = remote.objects.getValue(key)
        val modifiedManifest = codec.decode(validManifest).decodeToString()
            .replace(Digests.sha256Hex(valid), Digests.sha256Hex(damagedWire))
        remote.objects[manifestKey] = codec.encode(modifiedManifest.toByteArray())
        val aeadTampered = remote.snapshot()
        val badTag = assertThrows(SyncFailure::class.java) { runBlocking { BlobTransfer(remote, receiver, codec).synchronize() } }
        assertEquals(SyncError.ENCRYPTED_DATA_INVALID, badTag.error)
        assertEquals(0, receiver.installs)
        assertEquals(1, remote.puts.size)
        remote.assertSnapshot(aeadTampered)
        remote.objects[manifestKey] = validManifest
        remote.objects[key] = valid.copyOf()
        BlobTransfer(remote, receiver, codec).synchronize()
        assertEquals(1, receiver.installs)
        assertArrayEquals(bytes, receiver.bytes)
        assertEquals(hash, receiver.blob?.hash)
        assertEquals(1, remote.puts.size)
    }

    @Test fun concurrentEncryptedBlobCreationKeepsWinnersCiphertextAndPublishesItsHash() = runBlocking {
        val remote = Provider()
        val codec = crypto.payload(password)
        val bytes = "concurrent immutable blob".toByteArray()
        val hash = Digests.sha256Hex(bytes)
        val blob = SyncBlob(hash, "${hash.take(2)}/$hash.bin", bytes.size.toLong(), "2026-11-18T00:00:00Z")
        val key = "data_governance/blobs/${blob.relativePath}"
        val winnerWire = codec.encode(bytes, blob = true)
        // The first GET saw absence. A different writer wins just before our ifAbsent PUT.
        remote.insertBeforeNextConditionalPut = key to winnerWire.copyOf()
        BlobTransfer(remote, Blobs(blob, bytes), codec).synchronize()
        assertNull(remote.insertBeforeNextConditionalPut)
        assertArrayEquals(winnerWire, remote.objects.getValue(key))
        assertFalse(remote.puts.contains(key))
        val manifestKey = remote.puts.single()
        val manifest = JsonValue.parseObject(codec.decode(remote.objects.getValue(manifestKey)).decodeToString())
        val entry = (manifest["entries"] as JsonValue.Object)[hash] as JsonValue.Object
        assertEquals(Digests.sha256Hex(winnerWire), (entry["cipher_sha256"] as JsonValue.Text).value)
        assertEquals(winnerWire.size.toLong(), (entry["cipher_size"] as JsonValue.Number).asLongOrNull())
    }

    private suspend fun createRepository(remote: Provider): SyncConfiguration {
        val store = Store()
        val factory = object : ProviderFactory {
            override fun open(endpoint: String, username: String, root: String, password: CharArray) = remote
        }
        val identity = object : DeviceIdentity {
            override fun getOrCreate() = LOCAL
        }
        val service = PairingService(store, identity, factory, encryption = encryption)
        val result = service.pair(
            PairingMode.CREATE,
            PairingRequest(
                "https://fixture.invalid/dav",
                "fixture",
                "root",
                "webdav".toCharArray(),
                password.copyOf(),
            ),
        )
        assertTrue(result is SyncResult.Success)
        return (result as SyncResult.Success).value
    }

    // Mirrors the configured preflight boundary, but does not claim to exercise app/Android wiring.
    private suspend fun pipeline(
        remote: Provider,
        config: SyncConfiguration,
        journal: PublicationJournal,
        domain: Domain,
        codec: SyncPayloadCodec,
    ): SyncPipeline {
        val guarded = encryption.check(remote, true, password).guardWrites(remote)
        return SyncPipeline(
            ChangeTransfer(guarded, compression, journal, domain, LOCAL, codec),
            BlobTransfer(guarded, Blobs(), codec),
            { RemoteGate(guarded, config.instanceId).minimumReader() },
        )
    }

    private fun assertSafePoint(journal: PublicationJournal, domain: Domain, key: String, wire: ByteArray) {
        val pending = journal.pending()
        assertNotNull(pending)
        assertEquals(key, pending!!.key)
        assertEquals(1, pending.sequence)
        assertEquals(listOf(17L), pending.receipts.map { it.changeLogId })
        assertArrayEquals(wire, pending.payload)
        assertEquals(0, journal.published())
        assertFalse(domain.marked)
        assertEquals(0, domain.cursor(SyncWireCodec.deviceHash(PEER)))
        assertTrue(domain.applied.isEmpty())
    }

    private fun changeKeys(remote: Provider) = remote.objects.keys.filter { it.startsWith("data_governance/changes/") }

    private class Provider : ObjectProvider {
        val objects = linkedMapOf<String, ByteArray>()
        val puts = mutableListOf<String>()
        var loseNextPutResponse = false
        var insertBeforeNextConditionalPut: Pair<String, ByteArray>? = null
        override suspend fun get(key: String) = objects[key]?.copyOf()
        override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) {
            val competitor = insertBeforeNextConditionalPut
            if (ifAbsent && competitor?.first == key) {
                objects[key] = competitor.second.copyOf()
                insertBeforeNextConditionalPut = null
            }
            if (ifAbsent && key in objects) throw SyncFailure(SyncError.ALREADY_EXISTS)
            objects[key] = value.copyOf()
            puts += key
            if (loseNextPutResponse) {
                loseNextPutResponse = false
                throw SyncFailure(SyncError.NETWORK)
            }
        }
        override suspend fun list(prefix: String) = objects.keys.filter { it.startsWith(prefix) }
        override suspend fun delete(key: String) {
            objects.remove(key)
        }
        fun snapshot() = objects.mapValues { it.value.copyOf() }
        fun assertSnapshot(expected: Map<String, ByteArray>) {
            assertEquals(expected.keys, objects.keys)
            expected.forEach { (key, value) -> assertArrayEquals(key, value, objects[key]) }
        }
    }

    private class Store : ConfigurationStore {
        private var configuration: SyncConfiguration? = null
        override fun read() = configuration
        override fun save(configuration: SyncConfiguration, password: CharArray, encryptionPassword: CharArray?) {
            this.configuration =
                configuration
        }
        override fun replaceEncryptionPassword(configuration: SyncConfiguration, password: CharArray) = error("not used")
        override fun clear() {
            configuration = null
        }
    }

    private class VolatileState : SyncUploadState {
        var seq = 0L
        override fun published() = seq
        override fun markPublished(seq: Long) {
            this.seq = seq
        }
    }

    private class Domain(private val id: Long, private val note: String) : DomainSyncAdapter {
        var marked = false
        var markedIds = emptyList<Long>()
        var markCount = 0
        val applied = mutableListOf<ByteArray>()
        private val cursors = mutableMapOf<String, Long>()
        override fun pending() = if (marked) {
            emptyList()
        } else {
            listOf(
                (
                    """{"table_name":"notes","record_id":"$note","operation":"Insert","data":{},""" +
                        """"changed_at":"2026-11-18T00:00:00Z","change_log_id":$id}"""
                    ).toByteArray(),
            )
        }
        override fun markPublished(changeLogIds: List<Long>, version: Long) {
            marked = true
            markedIds = changeLogIds
            markCount++
        }
        override fun cursor(device: String) = cursors[device] ?: 0
        override fun apply(device: String, seq: Long, changes: List<ByteArray>) {
            check(seq == cursor(device) + 1)
            applied.addAll(changes.map { it.copyOf() })
            cursors[device] = seq
        }
    }

    private class Blobs(var blob: SyncBlob? = null, var bytes: ByteArray? = null) : SyncBlobs {
        var installs = 0
        override fun list() = listOfNotNull(blob)
        override fun read(blob: SyncBlob) = bytes?.copyOf()
        override fun install(blob: SyncBlob, bytes: ByteArray) {
            installs++
            this.blob = blob
            this.bytes = bytes.copyOf()
        }
    }

    companion object {
        private const val LOCAL = "android-fixture"
        private const val PEER = "desktop-fixture"
    }
}
