package com.deepstudent.services.resources.data

import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.services.resources.api.StreamAttachmentRequest
import com.deepstudent.services.resources.backend.AttachmentService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.time.Instant
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

class ContentAddressedAttachmentsTest {
    private val root = Files.createTempDirectory("s206-resources").toFile()
    private val file = root.resolve("vfs.db")
    private val blobDir = root.resolve("vfs_blobs")
    private val repositoryRoot = generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
        .first { java.io.File(it, "config/schema/final-lock.json").isFile }
    private val source = MigrationSource { repositoryRoot.resolve(it).readBytes() }
    private val clock = WallClock { Instant.parse("2026-11-20T00:00:00Z") }
    private val bytes = ByteArray(1024 * 1024 + 17) { (it % 251).toByte() }
    private val hash = Digests.sha256Hex(bytes)
    private val path get() = blobDir.resolve("${hash.take(2)}/$hash.bin")
    private val request = StreamAttachmentRequest("secret-name.bin", "application/octet-stream", bytes.size.toLong())

    @After fun cleanup() { root.deleteRecursively() }
    private fun database(): SqlDatabase = DatabaseFactory.openShared(file, SharedDatabase.VFS, source)
    private fun service(db: SqlDatabase, faults: ResourceFaults = ResourceFaults.NONE, space: AvailableSpace = AvailableSpace.REAL,
        logger: Logger = com.deepstudent.core.common.logging.NoopLogger) =
        AttachmentService(SqlAttachmentRepository(db, blobDir, clock, faults, space), logger)
    private fun names() = blobDir.walkTopDown().filter { it.isFile && it.name != ".publication.lock" }.map { it.relativeTo(blobDir).path }.toList()
    private fun count(db: SqlDatabase, table: String) = db.query("SELECT count(*) FROM $table") { it.long(0) }.single()

    private fun simulateProcessLoss(prepared: PreparedBlob) = synchronized(BlobFiles.publicationLock) {
        prepared.channel.close()
        BlobFiles.livePrepared.remove(prepared.path)
    }

    @Test fun productionSpaceUsesExistingDirectoryAndUnknownSpaceFailsClosed() = runBlocking {
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
            assertTrue(names().isEmpty()); assertEquals(0L, count(db, "files"))
        }
    }

    @Test fun streamingDeduplicatesAndPreservesSharedReferencesAcrossDeleteRestoreReopen() = runBlocking {
        var id = ""
        database().use { db ->
            val service = service(db)
            val progress = mutableListOf<Long>()
            val first = service.createStream(request, ByteArrayInputStream(bytes)) { progress.add(it) }
            val again = service.createStream(request.copy(name = "other.bin", expectedSize = null), ByteArrayInputStream(bytes))
            assertEquals(first.id, again.id)
            assertEquals(hash, first.sha256)
            assertEquals(bytes.size.toLong(), progress.last())
            assertEquals(1L, count(db, "files")); assertEquals(1L, count(db, "blobs")); assertEquals(1L, count(db, "resources"))
            db.execute("INSERT INTO vfs_index_units(id,resource_id,unit_index,image_blob_hash,created_at,updated_at) VALUES('i1',?,1,?,1,1),('i2',?,2,?,1,1)", listOf(text(first.resourceId.value), text(hash), text(first.resourceId.value), text(hash)))
            db.execute("DELETE FROM vfs_index_units WHERE id='i1'")
            val output = ByteArrayOutputStream()
            assertEquals(first.id, service.copyTo(first.id, output)?.id)
            assertArrayEquals(bytes, output.toByteArray())
            assertEquals(listOf(1L), db.query("SELECT count(*) FROM vfs_index_units WHERE image_blob_hash=?", listOf(text(hash))) { it.long(0) })
            db.execute("INSERT INTO vfs_index_units(id,resource_id,unit_index,image_blob_hash,created_at,updated_at) VALUES('i1',?,1,?,1,1)", listOf(text(first.resourceId.value), text(hash)))
            service.delete(first.id)
            assertNull(service.copyTo(first.id, ByteArrayOutputStream()))
            assertArrayEquals(bytes, ByteArrayOutputStream().also { service.copyTo(first.id, it, includeDeleted = true) }.toByteArray())
            service.restore(first.id)
            assertTrue(db.query("PRAGMA foreign_key_check") { it.long(0) }.isEmpty())
            id = first.id
        }
        database().use { db ->
            val output = ByteArrayOutputStream()
            assertEquals(id, service(db).copyTo(id, output)?.id)
            assertArrayEquals(bytes, output.toByteArray())
            assertEquals(listOf(2L), db.query("SELECT count(*) FROM vfs_index_units WHERE image_blob_hash=?", listOf(text(hash))) { it.long(0) })
        }
        assertEquals(listOf("${hash.take(2)}/$hash.bin"), names())
    }

    @Test fun partialReadPartialWritePublishAndTransactionFailuresLeaveNoVisibleBlobOrRows() = runBlocking {
        val cuts = listOf(ResourceCut.PREPARE_READ, ResourceCut.PREPARE_WRITE, ResourceCut.BEFORE_PUBLISH,
            ResourceCut.AFTER_MARKER, ResourceCut.AFTER_PUBLISH, ResourceCut.BEFORE_DATABASE, ResourceCut.BEFORE_COMMIT)
        database().use { db ->
            for (cut in cuts) {
                val faults = ResourceFaults { point, n -> if (point == cut && (point != ResourceCut.PREPARE_READ || n > 0)) throw IOException("secret-path") }
                assertThrows(Exception::class.java) { runBlocking { service(db, faults).createStream(request, ByteArrayInputStream(bytes)) } }
                assertFalse("cut=$cut", path.exists())
                assertTrue("cut=$cut remaining=${names()}", names().isEmpty())
                assertEquals("cut=$cut", 0L, count(db, "files")); assertEquals(0L, count(db, "blobs")); assertEquals(0L, count(db, "resources"))
            }
        }
    }

    @Test fun unknownSizeActualOverrunMismatchReadFailureAndSpaceFailureAreCleaned() = runBlocking {
        database().use { db ->
            val service = service(db)
            for (size in listOf(bytes.size.toLong() - 1, bytes.size.toLong() + 1)) {
                assertThrows(DstuException::class.java) { runBlocking { service.createStream(request.copy(expectedSize = size), ByteArrayInputStream(bytes)) } }
                assertTrue(names().isEmpty())
            }
            val broken = object : InputStream() {
                var reads = 0
                override fun read(): Int = throw IOException("secret")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (reads++ == 0) { b[off] = 1; return 1 }
                    throw IOException("secret")
                }
            }
            assertThrows(DstuException::class.java) { runBlocking { service.createStream(request.copy(expectedSize = null), broken) } }
            assertThrows(DstuException::class.java) { runBlocking { service(db, space = AvailableSpace { 0 }).createStream(request, ByteArrayInputStream(bytes)) } }
            assertTrue(names().isEmpty()); assertEquals(0L, count(db, "files"))
        }
    }

    @Test fun cancellationDuringPrepareAndBeforeCommitRemovesUnownedBytes() = runBlocking {
        database().use { db ->
            assertThrows(CancellationException::class.java) {
                runBlocking { val context = currentCoroutineContext(); service(db).createStream(request, ByteArrayInputStream(bytes)) { if (it > 0) context.cancel() } }
            }
            assertThrows(CancellationException::class.java) {
                runBlocking { service(db, ResourceFaults { cut, _ -> if (cut == ResourceCut.BEFORE_COMMIT) throw CancellationException("fixture") }).createStream(request, ByteArrayInputStream(bytes)) }
            }
            assertTrue(names().isEmpty()); assertEquals(0L, count(db, "files")); assertFalse(path.exists())
        }
    }

    @Test fun abandonedBeforeAndAfterPublicationRecoverByDurableDbOwnership() = runBlocking {
        database().use { db ->
            val raw = BlobFiles(blobDir)
            val partial = raw.prepare(ByteArrayInputStream("partial".toByteArray()), null, 100)
            simulateProcessLoss(partial) // simulate process death before publication
            val pending = raw.prepare(ByteArrayInputStream(bytes), null, BlobFiles.MAX_BYTES)
            raw.locked { raw.publish(pending) }
            simulateProcessLoss(pending) // simulate death after publication, before DB commit
            assertTrue(path.exists())
            service(db).createStream(StreamAttachmentRequest("wake", "text/plain"), ByteArrayInputStream(byteArrayOf(1)))
            assertFalse(path.exists())
            assertFalse(names().any { it.contains(".prepare-") || it.contains(".pending-") })
            val saved = service(db, ResourceFaults { cut, _ ->
                if (cut == ResourceCut.AFTER_COMMIT_CLEANUP) throw IOException("simulated post-commit cleanup failure")
            }).createStream(request, ByteArrayInputStream(bytes))
            val marker = blobDir.listFiles()!!.single { it.name.startsWith(".pending-") }.toPath()
            service(db).createStream(StreamAttachmentRequest("wake2", "text/plain"), ByteArrayInputStream(byteArrayOf(2)))
            assertTrue(path.exists()); assertFalse(Files.exists(marker))
            val out = ByteArrayOutputStream(); service(db).copyTo(saved.id, out); assertArrayEquals(bytes, out.toByteArray())
        }
    }

    @Test fun interruptionBetweenMoveAndIdentityRecordPreservesUncertainBlobUntilAdopted() = runBlocking {
        database().use { db ->
            val events = mutableListOf<LogRecord>()
            val logger = object : Logger { override fun log(record: LogRecord) { events += record } }
            val faults = ResourceFaults { cut, _ -> if (cut == ResourceCut.AFTER_MOVE) throw IOException("simulated death") }
            assertThrows(Exception::class.java) { runBlocking { service(db, faults, logger = logger).createStream(request, ByteArrayInputStream(bytes)) } }
            assertEquals(0L, count(db, "files")); assertArrayEquals(bytes, path.readBytes())
            assertTrue(names().any { it.contains(".pending-") })
            service(db, logger = logger).createStream(StreamAttachmentRequest("scan", "text/plain"), ByteArrayInputStream(byteArrayOf(8)))
            assertTrue(path.exists()); assertTrue(names().any { it.contains(".pending-") })
            assertTrue(events.joinToString().contains("recoverable"))
            assertTrue(events.joinToString().contains("OWNERSHIP_UNKNOWN"))
            SyncBlobStore(blobDir).install(hash, "${hash.take(2)}/$hash.bin", bytes.size.toLong(), bytes)
            assertArrayEquals(bytes, path.readBytes()); assertFalse(names().any { it.contains(".pending-") })
        }
    }

    @Test fun missingIdentityOrCorruptSidecarNeverDeletesAnUnprovenTarget() = runBlocking {
        val noIdentity = BlobFiles(blobDir, identities = BlobIdentityReader { null })
        val pending = noIdentity.prepare(ByteArrayInputStream(bytes), null, BlobFiles.MAX_BYTES)
        assertThrows(DstuException::class.java) { noIdentity.locked { noIdentity.publish(pending) } }
        pending.close(); assertFalse(path.exists()); assertTrue(names().isEmpty())
        database().use { db ->
            val raw = BlobFiles(blobDir)
            val prepared = raw.prepare(ByteArrayInputStream(bytes), null, BlobFiles.MAX_BYTES)
            raw.locked { raw.publish(prepared) }; simulateProcessLoss(prepared)
            val marker = blobDir.listFiles()!!.single { it.name.startsWith(".pending-") }
            marker.writeBytes(ByteArray(513) { 65 })
            service(db).createStream(StreamAttachmentRequest("scan", "text/plain"), ByteArrayInputStream(byteArrayOf(8)))
            assertArrayEquals(bytes, path.readBytes()); assertTrue(marker.exists())
        }
    }

    @Test fun internallyInconsistentOwnerRecordPreservesTarget() = runBlocking {
        database().use { db ->
            val raw = BlobFiles(blobDir)
            val prepared = raw.prepare(ByteArrayInputStream(bytes), null, BlobFiles.MAX_BYTES)
            raw.locked { raw.publish(prepared) }; simulateProcessLoss(prepared)
            val marker = blobDir.listFiles()!!.single { it.name.endsWith(".meta") }
            val lines = marker.readText().lines().toMutableList()
            val source = lines[1].split(' ').toMutableList()
            source[1] = (source[1].toLong() + 1).toString()
            lines[1] = source.joinToString(" ")
            marker.writeText(lines.joinToString("\n"))
            service(db).createStream(StreamAttachmentRequest("scan", "text/plain"), ByteArrayInputStream(byteArrayOf(8)))
            assertArrayEquals(bytes, path.readBytes()); assertTrue(marker.exists())
        }
    }

    @Test fun recoveryNeverDeletesExternallyReplacedTargetEvenWithSameBytes() = runBlocking {
        database().use { db ->
            val raw = BlobFiles(blobDir)
            val prepared = raw.prepare(ByteArrayInputStream(bytes), null, BlobFiles.MAX_BYTES)
            raw.locked { raw.publish(prepared) }; simulateProcessLoss(prepared)
            val original = root.resolve("original")
            Files.move(path.toPath(), original.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            path.writeBytes(bytes)
            val replacementIdentity = BlobIdentityReader.REAL.read(path.toPath())
            service(db).createStream(StreamAttachmentRequest("scan", "text/plain"), ByteArrayInputStream(byteArrayOf(8)))
            assertArrayEquals(bytes, path.readBytes()); assertEquals(replacementIdentity, BlobIdentityReader.REAL.read(path.toPath()))
            assertArrayEquals(bytes, original.readBytes()); assertFalse(names().any { it.contains(".pending-") })
        }
    }

    @Test fun replacementDuringReapIsRestoredToOriginalPathNeverUnlinked() = runBlocking {
        database().use { db ->
            val raw = BlobFiles(blobDir)
            val prepared = raw.prepare(ByteArrayInputStream(bytes), null, BlobFiles.MAX_BYTES)
            raw.locked { raw.publish(prepared) }; simulateProcessLoss(prepared)
            val replacement = "external-replacement".toByteArray()
            val original = root.resolve("original")
            val faults = ResourceFaults { cut, _ ->
                if (cut == ResourceCut.BEFORE_REAP_MOVE) {
                    Files.move(path.toPath(), original.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                    path.writeBytes(replacement)
                }
            }
            service(db, faults).createStream(StreamAttachmentRequest("scan", "text/plain"), ByteArrayInputStream(byteArrayOf(8)))
            assertArrayEquals(replacement, path.readBytes()); assertArrayEquals(bytes, original.readBytes())
            assertFalse(names().any { it.contains(".reap-") })
            assertTrue(names().any { it.contains(".pending-") })
            service(db).createStream(StreamAttachmentRequest("scan2", "text/plain"), ByteArrayInputStream(byteArrayOf(9)))
            assertArrayEquals(replacement, path.readBytes()); assertFalse(names().any { it.contains(".pending-") })
        }
    }

    @Test fun syncAdoptsUncommittedPublishedContentWithoutLaterRecoveryDeletingIt() = runBlocking {
        database().use { db ->
            val raw = BlobFiles(blobDir)
            val pending = raw.prepare(ByteArrayInputStream(bytes), null, BlobFiles.MAX_BYTES)
            raw.locked { raw.publish(pending) }
            simulateProcessLoss(pending)
            SyncBlobStore(blobDir).install(hash, "${hash.take(2)}/$hash.bin", bytes.size.toLong(), bytes)
            service(db).createStream(StreamAttachmentRequest("wake", "text/plain"), ByteArrayInputStream(byteArrayOf(3)))
            assertTrue(path.exists()); assertArrayEquals(bytes, SyncBlobStore(blobDir).read(hash, "${hash.take(2)}/$hash.bin", bytes.size.toLong()))
            assertFalse(names().any { it.contains(".pending-") })
        }
    }

    @Test fun failedWriterDoesNotRemovePreexistingBlobOrCommittedReference() = runBlocking {
        database().use { db ->
            val service = service(db)
            val first = service.createStream(request, ByteArrayInputStream(bytes))
            val failing = service(db, ResourceFaults { cut, _ -> if (cut == ResourceCut.BEFORE_COMMIT) throw IOException("secret") })
            assertThrows(Exception::class.java) { runBlocking { failing.createStream(request, ByteArrayInputStream(bytes)) } }
            assertTrue(path.exists()); assertArrayEquals(bytes, service.get(first.id)?.bytes); assertEquals(1L, count(db, "files"))
            path.delete() // persisted row still owns a replacement that a failed operation repairs
            assertThrows(Exception::class.java) { runBlocking { failing.createStream(request, ByteArrayInputStream(bytes)) } }
            assertTrue(path.exists()); assertArrayEquals(bytes, service.get(first.id)?.bytes)
        }
    }

    @Test fun failedSettlementCannotRaceSyncAdoptionBeforePreparedHandleCloses() {
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
                assertTrue(closing.await(15, TimeUnit.SECONDS)); assertTrue(path.exists())
                val sync = pool.submit {
                    syncStarted.countDown()
                    SyncBlobStore(blobDir).install(hash, "${hash.take(2)}/$hash.bin", bytes.size.toLong(), bytes)
                }
                assertTrue(syncStarted.await(10, TimeUnit.SECONDS))
                assertThrows(java.util.concurrent.TimeoutException::class.java) { sync.get(200, TimeUnit.MILLISECONDS) }
                release.countDown(); writer.get(15, TimeUnit.SECONDS); sync.get(15, TimeUnit.SECONDS)
                assertTrue(path.exists()); assertFalse(names().any { it.contains(".pending-") })
                runBlocking { service(db).createStream(StreamAttachmentRequest("recovery", "text/plain"), ByteArrayInputStream(byteArrayOf(7))) }
                assertTrue(path.exists()); assertArrayEquals(bytes, SyncBlobStore(blobDir).read(hash, "${hash.take(2)}/$hash.bin", bytes.size.toLong()))
            } finally { release.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun concurrentSameContentKeepsOneDurableFileAndId() {
        database().use { db ->
            val pool = Executors.newFixedThreadPool(2)
            val started = CountDownLatch(2); val proceed = CountDownLatch(1)
            try {
                val jobs = (1..2).map {
                    pool.submit<String> {
                        val input = object : ByteArrayInputStream(bytes) {
                            var first = true
                            override fun read(b: ByteArray, off: Int, len: Int): Int {
                                if (first) { first = false; started.countDown(); check(proceed.await(10, TimeUnit.SECONDS)) }
                                return super.read(b, off, len)
                            }
                        }
                        runBlocking { service(db).createStream(request, input).id }
                    }
                }
                assertTrue(started.await(10, TimeUnit.SECONDS)); proceed.countDown()
                assertEquals(jobs[0].get(20, TimeUnit.SECONDS), jobs[1].get(20, TimeUnit.SECONDS))
                assertEquals(1L, count(db, "files")); assertEquals(1L, count(db, "blobs")); assertArrayEquals(bytes, path.readBytes())
                assertEquals(listOf("${hash.take(2)}/$hash.bin"), names())
            } finally { proceed.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun recoverDoesNotDropLiveProcessLocksOrTouchUnrelatedHiddenFiles() {
        database().use { db ->
            val pool = Executors.newSingleThreadExecutor()
            val started = CountDownLatch(1); val proceed = CountDownLatch(1)
            try {
                val live = pool.submit {
                    val blocked = object : ByteArrayInputStream(bytes) {
                        var first = true
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (first) { first = false; started.countDown(); check(proceed.await(15, TimeUnit.SECONDS)) }
                            return super.read(b, off, len)
                        }
                    }
                    runBlocking { service(db).createStream(request, blocked) }
                }
                assertTrue(started.await(10, TimeUnit.SECONDS))
                val active = blobDir.listFiles()!!.single { it.name.startsWith(".prepare-") }
                val decoy = blobDir.resolve(".prepare-not-ours.tmp").apply { writeText("do not delete") }
                val malformed = blobDir.resolve(".prepare-${"a".repeat(36)}.tmp").apply { writeText("not a UUID") }
                runBlocking { service(db).createStream(StreamAttachmentRequest("scan", "text/plain"), ByteArrayInputStream(byteArrayOf(9))) }
                assertTrue(active.exists()); assertTrue(decoy.exists()); assertTrue(malformed.exists())
                val script = "import fcntl,sys; f=open(sys.argv[1],'r+'); " +
                    "\ntry: fcntl.lockf(f,fcntl.LOCK_EX | fcntl.LOCK_NB)\nexcept BlockingIOError: sys.exit(0)\nsys.exit(1)"
                val probe = ProcessBuilder("python3", "-c", script, active.absolutePath).redirectErrorStream(true).start()
                assertEquals(probe.inputStream.bufferedReader().readText(), 0, probe.waitFor())
                proceed.countDown(); live.get(20, TimeUnit.SECONDS)
                assertTrue(decoy.exists())
            } finally { proceed.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun unknownLengthIsBoundedWhileStreamingAndOutputCancellationPreservesCommittedBlob() = runBlocking {
        val raw = BlobFiles(blobDir)
        assertThrows(DstuException::class.java) { raw.prepare(ByteArrayInputStream(ByteArray(129)), null, 128) }
        assertTrue(names().isEmpty())
        database().use { db ->
            val service = service(db)
            val saved = service.createStream(request, ByteArrayInputStream(bytes))
            val partial = ByteArrayOutputStream()
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    val coroutine = currentCoroutineContext()
                    service.copyTo(saved.id, partial, onProgress = { if (it > 0) coroutine.cancel() })
                }
            }
            assertTrue(partial.size() in 1 until bytes.size)
            assertArrayEquals(bytes, service.get(saved.id)?.bytes)
        }
    }

    @Test fun otherProcessPreparationIsSkippedUntilItExits() {
        database().use { db ->
            blobDir.mkdirs()
            val path = blobDir.resolve(".prepare-${UUID.randomUUID()}.tmp")
            path.writeText("partial")
            val script = "import fcntl,sys; f=open(sys.argv[1],'r+'); fcntl.lockf(f,fcntl.LOCK_EX); " +
                "print('locked', flush=True); sys.stdin.read()"
            val process = ProcessBuilder("python3", "-c", script, path.absolutePath).redirectErrorStream(true).start()
            try {
                assertEquals("locked", process.inputStream.bufferedReader().readLine())
                runBlocking { service(db).createStream(StreamAttachmentRequest("scan", "text/plain"), ByteArrayInputStream(byteArrayOf(1))) }
                assertTrue(path.exists())
                process.outputStream.close(); assertEquals(0, process.waitFor())
                runBlocking { service(db).createStream(StreamAttachmentRequest("scan2", "text/plain"), ByteArrayInputStream(byteArrayOf(2))) }
                assertFalse(path.exists())
            } finally { process.destroyForcibly() }
        }
    }

    @Test fun commitIsReturnedEvenWhenCleanupOrCloseFailsAndNextWriteRecoversMarker() = runBlocking {
        database().use { db ->
            val events = mutableListOf<LogRecord>()
            val logger = object : Logger { override fun log(record: LogRecord) { events += record } }
            for (cut in listOf(ResourceCut.AFTER_COMMIT_CLEANUP, ResourceCut.AFTER_COMMIT_CLOSE)) {
                val content = bytes.copyOf().also { it[0] = cut.ordinal.toByte() }
                val saved = service(db, ResourceFaults { actual, _ -> if (cut == actual) throw IOException("private") }, logger = logger)
                    .createStream(request, ByteArrayInputStream(content))
                assertArrayEquals(content, service(db).get(saved.id)?.bytes)
                assertTrue(events.joinToString().contains("recoverable"))
                if (cut == ResourceCut.AFTER_COMMIT_CLEANUP) assertTrue(names().any { it.contains(".pending-") })
                service(db).createStream(StreamAttachmentRequest("recovery", "text/plain"), ByteArrayInputStream(byteArrayOf(cut.ordinal.toByte())))
                assertFalse(names().any { it.contains(".pending-") })
                assertArrayEquals(content, service(db).get(saved.id)?.bytes)
            }
            assertFalse(events.joinToString().contains("private"))
        }
    }

    @Test fun emptyStreamReportsZeroAndOutputFailureIsNotHashCorruption() = runBlocking {
        database().use { db ->
            val service = service(db)
            val inputProgress = mutableListOf<Long>()
            val saved = service.createStream(StreamAttachmentRequest("empty", "text/plain", 0), ByteArrayInputStream(byteArrayOf())) { inputProgress += it }
            assertEquals(listOf(0L), inputProgress)
            val outputProgress = mutableListOf<Long>()
            assertEquals(saved.id, service.copyTo(saved.id, ByteArrayOutputStream(), onProgress = { outputProgress += it })?.id)
            assertEquals(listOf(0L), outputProgress)
            val large = service.createStream(request, ByteArrayInputStream(bytes))
            val badOutput = object : java.io.OutputStream() {
                override fun write(value: Int) { throw IOException("private-output") }
                override fun write(b: ByteArray, off: Int, len: Int) { throw IOException("private-output") }
            }
            val error = assertThrows(DstuException::class.java) { runBlocking { service.copyTo(large.id, badOutput) } }
            assertEquals("RESOURCE_STORAGE_FAILED", error.error.code)
            assertEquals("OUTPUT", error.error.details["stage"])
            assertFalse(error.toString().contains("private-output"))
            assertArrayEquals(bytes, service.get(large.id)?.bytes)
        }
    }

    @Test fun phaseLogsShareCorrelationAndExcludePathsNamesAndFailureMessages() = runBlocking {
        database().use { db ->
            val events = mutableListOf<LogRecord>()
            val logger = object : Logger { override fun log(record: LogRecord) { events += record } }
            service(db, logger = logger).createStream(request, ByteArrayInputStream(bytes))
            assertEquals(1, events.map { it.correlationId }.toSet().size)
            val text = events.joinToString("\n")
            for (phase in listOf("PREFLIGHT", "PREPARE", "PUBLISH", "DATABASE", "CLEANUP")) assertTrue(phase, text.contains(phase))
            for (field in listOf("owner", "phase", "status", "durationMillis")) assertTrue(field, text.contains(field))
            assertFalse(text.contains("secret-name")); assertFalse(text.contains(root.path)); assertFalse(text.contains(hash))
            events.clear()
            assertThrows(Exception::class.java) { runBlocking { service(db, ResourceFaults { cut, _ -> if (cut == ResourceCut.PREPARE_WRITE) throw IOException("secret-payload") }, logger = logger).createStream(request, ByteArrayInputStream(bytes)) } }
            val failure = events.joinToString("\n"); assertTrue(failure.contains("failed")); assertFalse(failure.contains("secret")); assertFalse(failure.contains(root.path))
        }
    }
}
