package com.deepstudent.services.resources.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.time.SystemWallClock
import com.deepstudent.platform.database.BundledMigrationSource
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.services.resources.api.StreamAttachmentRequest
import com.deepstudent.services.resources.backend.AttachmentService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentAddressedAttachmentsAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "s2-06-content-${UUID.randomUUID()}").apply { mkdirs() }
    private val dbFile = root.resolve("vfs.db")
    private val blobDir = root.resolve("vfs_blobs")
    private val source = BundledMigrationSource(context)
    private val bytes = ByteArray(1024 * 1024 + 17) { (it % 251).toByte() }
    private val hash = Digests.sha256Hex(bytes)
    private val target get() = blobDir.resolve("${hash.take(2)}/$hash.bin")
    private val request = StreamAttachmentRequest("secret-name.bin", "application/octet-stream", bytes.size.toLong())

    @After fun cleanup() { root.deleteRecursively() }
    private fun database() = DatabaseFactory.openShared(dbFile, SharedDatabase.VFS, source)
    private fun service(db: SqlDatabase, faults: ResourceFaults = ResourceFaults.NONE, space: AvailableSpace = AvailableSpace.REAL,
        logger: Logger = com.deepstudent.core.common.logging.NoopLogger) =
        AttachmentService(SqlAttachmentRepository(db, blobDir, SystemWallClock, faults, space), logger)
    private fun files() = blobDir.walkTopDown().filter { it.isFile && it.name != ".publication.lock" }.map { it.relativeTo(blobDir).path }.toList()
    private fun count(db: SqlDatabase, table: String) = db.query("SELECT count(*) FROM $table") { it.long(0) }.single()
    private fun simulateProcessLoss(prepared: PreparedBlob) = synchronized(BlobFiles.publicationLock) {
        prepared.channel.close()
        BlobFiles.livePrepared.remove(prepared.path)
    }

    @Test fun streamingDedupeSharedReferencesAndReopen() = runBlocking {
        var savedId = ""
        database().use { db ->
            val service = service(db)
            val progress = mutableListOf<Long>()
            val saved = service.createStream(request, ByteArrayInputStream(bytes)) { progress += it }
            val duplicate = service.createStream(request.copy(expectedSize = null), ByteArrayInputStream(bytes))
            assertEquals(saved.id, duplicate.id); assertEquals(hash, saved.sha256)
            assertEquals(0L, progress.first()); assertEquals(bytes.size.toLong(), progress.last())
            assertEquals(1L, count(db, "files")); assertEquals(1L, count(db, "blobs"))
            db.execute("INSERT INTO vfs_index_units(id,resource_id,unit_index,image_blob_hash,created_at,updated_at) VALUES('i1',?,1,?,1,1),('i2',?,2,?,1,1)", listOf(text(saved.resourceId.value), text(hash), text(saved.resourceId.value), text(hash)))
            db.execute("DELETE FROM vfs_index_units WHERE id='i1'")
            assertArrayEquals(bytes, ByteArrayOutputStream().also { service.copyTo(saved.id, it) }.toByteArray())
            assertEquals(listOf(1L), db.query("SELECT count(*) FROM vfs_index_units WHERE image_blob_hash=?", listOf(text(hash))) { it.long(0) })
            db.execute("INSERT INTO vfs_index_units(id,resource_id,unit_index,image_blob_hash,created_at,updated_at) VALUES('i1',?,1,?,1,1)", listOf(text(saved.resourceId.value), text(hash)))
            service.delete(saved.id); assertNull(service.copyTo(saved.id, ByteArrayOutputStream()))
            assertArrayEquals(bytes, ByteArrayOutputStream().also { service.copyTo(saved.id, it, true) }.toByteArray())
            service.restore(saved.id)
            assertTrue(db.query("PRAGMA foreign_key_check") { it.long(0) }.isEmpty())
            savedId = saved.id
        }
        database().use { db ->
            assertArrayEquals(bytes, ByteArrayOutputStream().also { service(db).copyTo(savedId, it) }.toByteArray())
            assertEquals(listOf(2L), db.query("SELECT count(*) FROM vfs_index_units WHERE image_blob_hash=?", listOf(text(hash))) { it.long(0) })
        }
    }

    @Test fun faultCutsAndCancellationLeaveNoPublishedObjectOrRows() = runBlocking {
        val cuts = listOf(ResourceCut.PREPARE_READ, ResourceCut.PREPARE_WRITE, ResourceCut.BEFORE_PUBLISH,
            ResourceCut.AFTER_MARKER, ResourceCut.AFTER_PUBLISH, ResourceCut.BEFORE_DATABASE, ResourceCut.BEFORE_COMMIT)
        database().use { db ->
            for (cut in cuts) {
                val faults = ResourceFaults { point, count -> if (point == cut && (cut != ResourceCut.PREPARE_READ || count > 0)) throw IOException("private") }
                assertThrows(Exception::class.java) { runBlocking { service(db, faults).createStream(request, ByteArrayInputStream(bytes)) } }
                assertFalse(cut.name, target.exists()); assertTrue("$cut: ${files()}", files().isEmpty())
                assertEquals(0L, count(db, "files")); assertEquals(0L, count(db, "blobs"))
            }
            assertThrows(CancellationException::class.java) {
                runBlocking { val coroutine = currentCoroutineContext(); service(db).createStream(request, ByteArrayInputStream(bytes)) { if (it > 0) coroutine.cancel() } }
            }
            assertFalse(target.exists()); assertTrue(files().isEmpty())
        }
    }

    @Test fun productionSpaceUsesPrivateDirectoryAndZeroSpaceFailsClosed() = runBlocking {
        assertTrue(AvailableSpace.REAL.bytes(root.toPath()) > 0)
        assertEquals(0L, AvailableSpace.REAL.bytes(root.resolve("missing").toPath()))
        database().use { db ->
            assertThrows(DstuException::class.java) {
                runBlocking { service(db, space = AvailableSpace { 0 }).createStream(request, ByteArrayInputStream(bytes)) }
            }
            assertThrows(DstuException::class.java) {
                runBlocking {
                    service(db, space = AvailableSpace { 0 }).createStream(
                        StreamAttachmentRequest("empty", "text/plain", 0), ByteArrayInputStream(byteArrayOf()),
                    )
                }
            }
            assertTrue(files().isEmpty()); assertEquals(0L, count(db, "files"))
        }
    }

    @Test fun sizeSpaceAndEmptyProgressAreEnforced() = runBlocking {
        database().use { db ->
            assertThrows(DstuException::class.java) { runBlocking { service(db).createStream(request.copy(expectedSize = bytes.size.toLong() + 1), ByteArrayInputStream(bytes)) } }
            assertThrows(DstuException::class.java) { runBlocking { service(db, space = AvailableSpace { 0 }).createStream(request, ByteArrayInputStream(bytes)) } }
            assertTrue(files().isEmpty())
            val progress = mutableListOf<Long>()
            val empty = service(db).createStream(StreamAttachmentRequest("empty", "text/plain", 0), ByteArrayInputStream(byteArrayOf())) { progress += it }
            assertEquals(listOf(0L), progress)
            val readProgress = mutableListOf<Long>()
            service(db).copyTo(empty.id, ByteArrayOutputStream(), onProgress = { readProgress += it })
            assertEquals(listOf(0L), readProgress)
        }
    }

    @Test fun abandonedMarkersAreRecoveredAndSyncAdoptionIsPreserved() = runBlocking {
        database().use { db ->
            val raw = BlobFiles(blobDir)
            val prepared = raw.prepare(ByteArrayInputStream(bytes), null, BlobFiles.MAX_BYTES)
            raw.locked { raw.publish(prepared) }; simulateProcessLoss(prepared)
            assertTrue(target.exists())
            service(db).createStream(StreamAttachmentRequest("scan", "text/plain"), ByteArrayInputStream(byteArrayOf(1)))
            assertFalse(target.exists())
            val next = raw.prepare(ByteArrayInputStream(bytes), null, BlobFiles.MAX_BYTES)
            raw.locked { raw.publish(next) }; simulateProcessLoss(next)
            val sync = SyncBlobStore(blobDir)
            sync.install(hash, "${hash.take(2)}/$hash.bin", bytes.size.toLong(), bytes)
            service(db).createStream(StreamAttachmentRequest("scan2", "text/plain"), ByteArrayInputStream(byteArrayOf(2)))
            assertArrayEquals(bytes, sync.read(hash, "${hash.take(2)}/$hash.bin", bytes.size.toLong()))
            assertFalse(files().any { it.contains(".pending-") })
        }
    }

    @Test fun moveIdentityAndIncompleteMarkerFailClosedUntilSyncAdopts() = runBlocking {
        database().use { db ->
            val events = mutableListOf<LogRecord>()
            val logger = object : Logger { override fun log(record: LogRecord) { events += record } }
            val faults = ResourceFaults { cut, _ -> if (cut == ResourceCut.AFTER_MOVE) throw IOException("injected") }
            assertThrows(Exception::class.java) { runBlocking { service(db, faults, logger = logger).createStream(request, ByteArrayInputStream(bytes)) } }
            assertEquals(0L, count(db, "files")); assertArrayEquals(bytes, target.readBytes())
            assertTrue(files().any { it.endsWith(".meta") })
            service(db, logger = logger).createStream(StreamAttachmentRequest("scan", "text/plain"), ByteArrayInputStream(byteArrayOf(8)))
            assertArrayEquals(bytes, target.readBytes()); assertTrue(events.joinToString().contains("OWNERSHIP_UNKNOWN"))
            SyncBlobStore(blobDir).install(hash, "${hash.take(2)}/$hash.bin", bytes.size.toLong(), bytes)
            assertArrayEquals(bytes, target.readBytes()); assertFalse(files().any { it.endsWith(".meta") })
        }
    }

    @Test fun unknownIdentityAndInvalidMarkerPreserveUnprovenFile() = runBlocking {
        val noIdentity = BlobFiles(blobDir, identities = BlobIdentityReader { null })
        val unavailable = noIdentity.prepare(ByteArrayInputStream(bytes), null, BlobFiles.MAX_BYTES)
        assertThrows(DstuException::class.java) { noIdentity.locked { noIdentity.publish(unavailable) } }
        unavailable.close(); assertFalse(target.exists()); assertTrue(files().isEmpty())
        database().use { db ->
            val raw = BlobFiles(blobDir)
            val prepared = raw.prepare(ByteArrayInputStream(bytes), null, BlobFiles.MAX_BYTES)
            raw.locked { raw.publish(prepared) }; simulateProcessLoss(prepared)
            val marker = blobDir.listFiles()!!.single { it.name.endsWith(".meta") }
            marker.writeBytes(ByteArray(513) { 65 })
            service(db).createStream(StreamAttachmentRequest("scan", "text/plain"), ByteArrayInputStream(byteArrayOf(8)))
            assertArrayEquals(bytes, target.readBytes()); assertTrue(marker.exists())
        }
    }

    @Test fun identicalExternalReplacementIsNotDeletedByRecovery() = runBlocking {
        database().use { db ->
            val raw = BlobFiles(blobDir)
            val prepared = raw.prepare(ByteArrayInputStream(bytes), null, BlobFiles.MAX_BYTES)
            raw.locked { raw.publish(prepared) }; simulateProcessLoss(prepared)
            val original = root.resolve("original")
            Files.move(target.toPath(), original.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            target.writeBytes(bytes)
            val identity = BlobIdentityReader.REAL.read(target.toPath())
            assertNotNull(identity)
            service(db).createStream(StreamAttachmentRequest("scan", "text/plain"), ByteArrayInputStream(byteArrayOf(8)))
            assertArrayEquals(bytes, target.readBytes()); assertEquals(identity, BlobIdentityReader.REAL.read(target.toPath()))
            assertArrayEquals(bytes, original.readBytes()); assertFalse(files().any { it.endsWith(".meta") })
        }
    }

    @Test fun externalReplacementDuringReapIsRestoredNotDeleted() = runBlocking {
        database().use { db ->
            val raw = BlobFiles(blobDir)
            val prepared = raw.prepare(ByteArrayInputStream(bytes), null, BlobFiles.MAX_BYTES)
            raw.locked { raw.publish(prepared) }; simulateProcessLoss(prepared)
            val replacement = "external-replacement".toByteArray()
            val original = root.resolve("original")
            val faults = ResourceFaults { cut, _ ->
                if (cut == ResourceCut.BEFORE_REAP_MOVE) {
                    Files.move(target.toPath(), original.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                    target.writeBytes(replacement)
                }
            }
            service(db, faults).createStream(StreamAttachmentRequest("scan", "text/plain"), ByteArrayInputStream(byteArrayOf(8)))
            assertArrayEquals(replacement, target.readBytes()); assertArrayEquals(bytes, original.readBytes())
            assertTrue(files().any { it.endsWith(".meta") }); assertFalse(files().any { it.contains(".reap-") })
            service(db).createStream(StreamAttachmentRequest("scan2", "text/plain"), ByteArrayInputStream(byteArrayOf(9)))
            assertArrayEquals(replacement, target.readBytes()); assertFalse(files().any { it.endsWith(".meta") })
        }
    }

    @Test fun committedCleanupFailureReturnsSuccessAndNextWriteRecovers() = runBlocking {
        database().use { db ->
            val events = mutableListOf<LogRecord>()
            val logger = object : Logger { override fun log(record: LogRecord) { events += record } }
            val faulty = service(db, ResourceFaults { cut, _ -> if (cut == ResourceCut.AFTER_COMMIT_CLEANUP) throw IOException("private") }, logger = logger)
            val saved = faulty.createStream(request, ByteArrayInputStream(bytes))
            assertArrayEquals(bytes, service(db).get(saved.id)?.bytes)
            assertTrue(files().any { it.contains(".pending-") })
            service(db).createStream(StreamAttachmentRequest("scan", "text/plain"), ByteArrayInputStream(byteArrayOf(1)))
            assertFalse(files().any { it.contains(".pending-") })
            assertArrayEquals(bytes, service(db).get(saved.id)?.bytes)
            assertTrue(events.joinToString().contains("recoverable")); assertFalse(events.joinToString().contains("private"))
        }
    }

    @Test fun concurrentIdenticalWritersPublishOnlyOneObject() {
        database().use { db ->
            val pool = Executors.newFixedThreadPool(2); val started = CountDownLatch(2); val proceed = CountDownLatch(1)
            try {
                val futures = (1..2).map {
                    pool.submit<String> {
                        val input = object : ByteArrayInputStream(bytes) {
                            var first = true
                            override fun read(b: ByteArray, off: Int, len: Int): Int {
                                if (first) { first = false; started.countDown(); check(proceed.await(15, TimeUnit.SECONDS)) }
                                return super.read(b, off, len)
                            }
                        }
                        runBlocking { service(db).createStream(request, input).id }
                    }
                }
                assertTrue(started.await(15, TimeUnit.SECONDS)); proceed.countDown()
                assertEquals(futures[0].get(30, TimeUnit.SECONDS), futures[1].get(30, TimeUnit.SECONDS))
                assertEquals(1L, count(db, "files")); assertEquals(1L, count(db, "blobs")); assertArrayEquals(bytes, target.readBytes())
            } finally { proceed.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun rollbackCleanupFailureCannotRaceSyncAdoption() {
        database().use { db ->
            val pool = Executors.newFixedThreadPool(2)
            val closing = CountDownLatch(1); val release = CountDownLatch(1); val syncStarted = CountDownLatch(1)
            val faults = ResourceFaults { cut, _ ->
                when (cut) {
                    ResourceCut.BEFORE_COMMIT, ResourceCut.BEFORE_SETTLE -> throw IOException("injected")
                    ResourceCut.AFTER_COMMIT_CLOSE -> { closing.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
                    else -> Unit
                }
            }
            try {
                val writer = pool.submit {
                    assertThrows(Exception::class.java) { runBlocking { service(db, faults).createStream(request, ByteArrayInputStream(bytes)) } }
                }
                assertTrue(closing.await(15, TimeUnit.SECONDS)); assertTrue(target.exists())
                val sync = pool.submit {
                    syncStarted.countDown()
                    SyncBlobStore(blobDir).install(hash, "${hash.take(2)}/$hash.bin", bytes.size.toLong(), bytes)
                }
                assertTrue(syncStarted.await(10, TimeUnit.SECONDS))
                assertThrows(java.util.concurrent.TimeoutException::class.java) { sync.get(200, TimeUnit.MILLISECONDS) }
                release.countDown(); writer.get(15, TimeUnit.SECONDS); sync.get(15, TimeUnit.SECONDS)
                assertFalse(files().any { it.contains(".pending-") })
                runBlocking { service(db).createStream(StreamAttachmentRequest("scan", "text/plain"), ByteArrayInputStream(byteArrayOf(7))) }
                assertArrayEquals(bytes, SyncBlobStore(blobDir).read(hash, "${hash.take(2)}/$hash.bin", bytes.size.toLong()))
            } finally { release.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun outputFailureAndLogsAreRedacted() = runBlocking {
        database().use { db ->
            val records = mutableListOf<LogRecord>(); val logger = object : Logger { override fun log(record: LogRecord) { records += record } }
            val service = service(db, logger = logger)
            val saved = service.createStream(request, ByteArrayInputStream(bytes))
            val failed = object : java.io.OutputStream() {
                override fun write(b: Int) { throw IOException("secret-output") }
                override fun write(b: ByteArray, off: Int, len: Int) { throw IOException("secret-output") }
            }
            val error = assertThrows(DstuException::class.java) { runBlocking { service.copyTo(saved.id, failed) } }
            assertEquals("RESOURCE_STORAGE_FAILED", error.error.code)
            assertEquals("OUTPUT", error.error.details["stage"])
            val trace = records.takeWhile { it.fields.toString().contains("createStream").not() }
            assertTrue(records.joinToString().contains("PUBLISH"))
            assertFalse(records.joinToString().contains("secret")); assertFalse(records.joinToString().contains(root.path))
            assertTrue(trace.all { it.correlationId != null })
            assertArrayEquals(bytes, service.get(saved.id)?.bytes)
        }
    }
}
