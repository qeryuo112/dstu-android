package com.deepstudent.services.resources.backend

import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.time.SystemMonotonicClock
import com.deepstudent.services.resources.api.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DocumentTransferTest {
    private val records = mutableListOf<LogRecord>()
    private val logger = object : Logger { override fun log(record: LogRecord) { records.add(record) } }
    private val transfer = DocumentTransfer(logger, SystemMonotonicClock)
    private val bytes = ByteArray(512 * 1024 + 17) { (it % 251).toByte() }
    private val digest get() = digest(bytes)

    @Test fun exportStreamsAndReadsBackWithHashAndProgress() = runBlocking {
        val target = Destination()
        val progress = mutableListOf<DocumentProgress>()
        val result = transfer.export(source(bytes), target, DocumentCancellation(), DocumentProgressObserver { progress.add(it) })
        assertEquals(DocumentExportResult.Verified(digest), result)
        assertArrayEquals(bytes, target.bytes)
        assertTrue(target.maxWrite <= 16 * 1024)
        assertTrue(progress.any { it.phase == DocumentPhase.COPY && it.bytes == bytes.size.toLong() })
        assertTrue(progress.any { it.phase == DocumentPhase.VERIFY && it.bytes == bytes.size.toLong() })
        assertFalse(target.deleted)
    }

    @Test fun emptyExportHasValidHash() = runBlocking {
        val result = transfer.export(source(byteArrayOf()), Destination(), DocumentCancellation())
        assertEquals(DocumentExportResult.Verified(digest(byteArrayOf())), result)
    }

    @Test fun exportDetectsWrongAuthoritativeHashBeforeClose() = runBlocking {
        val target = Destination()
        val result = transfer.export(source(bytes, digest(byteArrayOf(1))), target, DocumentCancellation())
        assertEquals(DocumentExportResult.Failed(DocumentPhase.COPY, DocumentFailure.DIGEST_MISMATCH, DocumentCleanup.DELETE_REPORTED), result)
        assertTrue(target.aborted)
    }

    @Test fun providerWriteFailureAbortsAndDeletesNewTarget() = runBlocking {
        val target = Destination().apply { failWrite = true }
        val result = transfer.export(source(bytes), target, DocumentCancellation())
        assertEquals(DocumentExportResult.Failed(DocumentPhase.COPY, DocumentFailure.IO, DocumentCleanup.DELETE_REPORTED), result)
        assertTrue(target.aborted)
        assertTrue(target.deleted)
    }

    @Test fun failureReportsPossiblePartialIfDeleteRefused() = runBlocking {
        val target = Destination().apply { failWrite = true; deleteSucceeds = false }
        val result = transfer.export(source(bytes), target, DocumentCancellation())
        assertEquals(DocumentExportResult.Failed(DocumentPhase.COPY, DocumentFailure.IO, DocumentCleanup.MAY_REMAIN), result)
    }

    @Test fun failureReportsPossiblePartialIfDeleteThrows() = runBlocking {
        val target = Destination().apply { failWrite = true; deleteThrows = true }
        val result = transfer.export(source(bytes), target, DocumentCancellation())
        assertEquals(DocumentExportResult.Failed(DocumentPhase.COPY, DocumentFailure.IO, DocumentCleanup.MAY_REMAIN), result)
    }

    @Test fun alteredProviderReadbackNeverReportedVerified() = runBlocking {
        val target = Destination().apply { corruptReadback = true }
        val result = transfer.export(source(bytes), target, DocumentCancellation())
        assertEquals(DocumentExportResult.Failed(DocumentPhase.VERIFY, DocumentFailure.DIGEST_MISMATCH, DocumentCleanup.DELETE_REPORTED), result)
    }

    @Test fun readbackStopsAtFirstUnexpectedTrailingByteAndCloses() = runBlocking {
        val delegate = Destination()
        var served = 0L
        var closed = false
        val target = object : DocumentNewDestination by delegate {
            override fun openRead(cancellation: DocumentCancellation): InputStream = object : InputStream() {
                override fun read(): Int = throw AssertionError("expected bounded block read")
                override fun read(output: ByteArray, offset: Int, length: Int): Int {
                    val count = minOf(bytes.size + 1L - served, length.toLong()).toInt()
                    check(count > 0) { "reader continued after an overlong response" }
                    for (i in 0 until count) output[offset + i] = bytes.getOrElse((served + i).toInt()) { 1 }
                    served += count
                    return count
                }
                override fun close() { closed = true }
            }
        }
        val result = transfer.export(source(bytes), target, DocumentCancellation())
        assertEquals(DocumentExportResult.Failed(DocumentPhase.VERIFY, DocumentFailure.DIGEST_MISMATCH,
            DocumentCleanup.DELETE_REPORTED), result)
        assertEquals(bytes.size + 1L, served)
        assertTrue(closed)
        assertTrue(delegate.deleted)
    }

    @Test fun revokedGrantDuringReadbackLeavesExplicitPartialState() = runBlocking {
        val target = Destination().apply { rejectRead = true; deleteSucceeds = false }
        val result = transfer.export(source(bytes), target, DocumentCancellation())
        assertEquals(DocumentExportResult.Failed(DocumentPhase.VERIFY, DocumentFailure.ACCESS_DENIED, DocumentCleanup.MAY_REMAIN), result)
    }

    @Test fun cancellationBeforeStartDeletesNewTarget() = runBlocking {
        val target = Destination()
        val token = DocumentCancellation().apply { cancel() }
        val result = transfer.export(source(bytes), target, token)
        assertEquals(DocumentExportResult.Cancelled(DocumentPhase.OPEN_DESTINATION, DocumentCleanup.DELETE_REPORTED), result)
        assertTrue(target.deleted)
    }

    @Test fun cancellationBeforeStartCanLeavePartialWhenDeletionIsUnavailable() = runBlocking {
        val target = Destination().apply { deleteSucceeds = false }
        val result = transfer.export(source(bytes), target, DocumentCancellation().apply { cancel() })
        assertEquals(DocumentExportResult.Cancelled(DocumentPhase.OPEN_DESTINATION, DocumentCleanup.MAY_REMAIN), result)
    }

    @Test fun failedTransferCanResumeOnlyIntoAnotherFreshTarget() = runBlocking {
        val failed = Destination().apply { failWrite = true; deleteSucceeds = false }
        assertTrue(transfer.export(source(bytes), failed, DocumentCancellation()) is DocumentExportResult.Failed)
        val fresh = Destination()
        assertEquals(DocumentExportResult.Verified(digest), transfer.export(source(bytes), fresh, DocumentCancellation()))
        assertArrayEquals(bytes, fresh.bytes)
    }

    @Test fun cancellationDuringCopyAbortsAndCleans() = runBlocking {
        val target = Destination()
        val token = DocumentCancellation()
        val result = transfer.export(source(bytes), target, token, DocumentProgressObserver { token.cancel() })
        assertEquals(DocumentExportResult.Cancelled(DocumentPhase.COPY, DocumentCleanup.DELETE_REPORTED), result)
        assertTrue(target.aborted)
    }

    @Test fun cancellationDuringReadbackCleansNewTarget() = runBlocking {
        val target = Destination()
        val token = DocumentCancellation()
        val result = transfer.export(source(bytes), target, token, DocumentProgressObserver {
            if (it.phase == DocumentPhase.VERIFY) token.cancel()
        })
        assertEquals(DocumentExportResult.Cancelled(DocumentPhase.VERIFY, DocumentCleanup.DELETE_REPORTED), result)
    }

    @Test fun successfulTargetCannotBeUsedTwiceOrDeletedBySecondCall() = runBlocking {
        val target = Destination()
        assertTrue(transfer.export(source(bytes), target, DocumentCancellation()) is DocumentExportResult.Verified)
        assertEquals(DocumentExportResult.Failed(DocumentPhase.OPEN_DESTINATION, DocumentFailure.INVALID_TARGET,
            DocumentCleanup.NOT_ATTEMPTED), transfer.export(source(bytes), target, DocumentCancellation()))
        assertFalse(target.deleted)
        assertArrayEquals(bytes, target.bytes)
    }

    @Test fun importPassesStreamAndDigestToAtomicWriter() = runBlocking {
        val writer = AtomicWriter()
        val result = transfer.import(input(bytes), writer, DocumentCancellation())
        assertEquals(DocumentImportResult.Stored("resource-id", digest), result)
        assertArrayEquals(bytes, writer.committed)
        assertTrue(writer.maxRead <= 16 * 1024)
    }

    @Test fun importReadFailureDoesNotCommitFixtureWriter() = runBlocking {
        val writer = AtomicWriter()
        val source = DocumentSource { object : InputStream() {
            override fun read(): Int = throw IOException("secret URI and data")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("secret URI and data")
        } }
        val result = transfer.import(source, writer, DocumentCancellation())
        assertEquals(DocumentImportResult.Failed(DocumentPhase.COPY, DocumentFailure.IO), result)
        assertNull(writer.committed)
    }

    @Test fun importCancellationLeavesNoCommittedFixtureResource() = runBlocking {
        val writer = AtomicWriter()
        val token = DocumentCancellation()
        val result = transfer.import(input(bytes), writer, token, DocumentProgressObserver { token.cancel() })
        assertEquals(DocumentImportResult.Cancelled(DocumentPhase.COPY), result)
        assertNull(writer.committed)
    }

    @Test fun cancelAfterEofStillCancelsWriterBeforeCommit() = runBlocking {
        val prepared = kotlinx.coroutines.CompletableDeferred<Unit>()
        val token = DocumentCancellation()
        var committed = false
        val writer = DocumentImportWriter { input, _ ->
            while (input.read() >= 0) { }
            prepared.complete(Unit)
            delay(Long.MAX_VALUE)
            currentCoroutineContext().ensureActive()
            committed = true
            "unreachable"
        }
        val worker = async(Dispatchers.Default) { transfer.import(input(bytes), writer, token) }
        prepared.await()
        token.cancel()
        assertEquals(DocumentImportResult.Cancelled(DocumentPhase.COPY), worker.await())
        assertFalse(committed)
    }

    @Test fun cancellationAfterWriterCommittedDoesNotHideCommittedResult() = runBlocking {
        val token = DocumentCancellation()
        val writer = DocumentImportWriter { input, _ ->
            while (input.read() >= 0) { }
            token.cancel() // model cancellation racing just after successful database commit
            "committed"
        }
        assertEquals(DocumentImportResult.Stored("committed", digest), transfer.import(input(bytes), writer, token))
    }

    @Test fun closingProviderAfterAtomicCommitCannotReportWriteFailure() = runBlocking {
        val source = DocumentSource { object : ByteArrayInputStream(bytes) {
            override fun close() { throw IOException("secret provider details") }
        } }
        val writer = AtomicWriter()
        assertEquals(DocumentImportResult.Stored("resource-id", digest), transfer.import(source, writer, DocumentCancellation()))
        assertArrayEquals(bytes, writer.committed)
        assertTrue(records.any { it.fields["phase"].toString().contains("CLEANUP") })
    }

    @Test fun importDetectsWriterNotReadingToEof() = runBlocking {
        val writer = DocumentImportWriter { stream, _ -> stream.read(); "not-a-real-store" }
        val result = transfer.import(input(bytes), writer, DocumentCancellation())
        assertEquals(DocumentImportResult.Failed(DocumentPhase.COPY, DocumentFailure.IO), result)
    }

    @Test fun coroutineCancellationReachesTransferOwner() = runBlocking {
        val target = Destination()
        val token = DocumentCancellation()
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val worker = async(Dispatchers.Default) {
            transfer.export(DocumentExportSource { _, _ -> started.complete(Unit); delay(Long.MAX_VALUE); digest }, target, token)
        }
        started.await()
        worker.cancel()
        try { worker.await(); fail("expected cancellation") } catch (_: CancellationException) { }
        assertTrue(token.isCancelled)
        assertTrue(target.aborted)
        assertTrue(target.deleted)
    }

    @Test fun logsHaveCorrelationPhaseStateElapsedAndNoPayload() = runBlocking {
        val target = Destination().apply { failWrite = true }
        transfer.export(source(bytes), target, DocumentCancellation())
        assertTrue(records.size >= 3)
        assertEquals(1, records.map { it.correlationId }.toSet().size)
        records.forEach {
            assertNotNull(it.correlationId)
            assertNotNull(it.fields["owner"])
            assertNotNull(it.fields["phase"])
            assertNotNull(it.fields["state"])
            assertNotNull(it.fields["elapsedMs"])
            assertFalse(it.fields.toString().contains("secret"))
        }
    }

    @Test fun selectionCorrelationPropagatesAcrossTransferPhases() = runBlocking {
        val id = CorrelationId.parse("selection-correlation-123")
        val delegate = Destination()
        val target = object : DocumentNewDestination by delegate { override val correlationId = id }
        assertTrue(transfer.export(source(bytes), target, DocumentCancellation()) is DocumentExportResult.Verified)
        assertTrue(records.isNotEmpty())
        assertTrue(records.all { it.correlationId == id })
    }

    @Test fun cancellationRegistrationsAreOnceOnlyAndCanBeRemoved() {
        val token = DocumentCancellation()
        var calls = 0
        token.onCancel { calls++ }
        token.onCancel { calls += 100 }.close()
        token.cancel(); token.cancel()
        token.onCancel { calls += 10 }
        assertEquals(11, calls)
    }

    @Test fun misbehavingObserverAndLoggerDoNotCorruptExport() = runBlocking {
        val throwing = DocumentTransfer(object : Logger { override fun log(record: LogRecord) { throw IllegalStateException("no") } }, SystemMonotonicClock)
        assertEquals(DocumentExportResult.Verified(digest), throwing.export(source(bytes), Destination(), DocumentCancellation(),
            DocumentProgressObserver { error("no") }))
    }

    private fun input(data: ByteArray) = DocumentSource { ByteArrayInputStream(data) }
    private fun source(data: ByteArray, expected: AssetDigest = digest(data)) = DocumentExportSource { output, _ ->
        output.write(data); expected
    }
    private fun digest(data: ByteArray) = AssetDigest(Digests.sha256Hex(data), data.size.toLong())

    private class AtomicWriter : DocumentImportWriter<String> {
        var committed: ByteArray? = null
        var maxRead = 0
        override suspend fun importAtomically(source: InputStream, cancellation: DocumentCancellation): String {
            val staged = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                cancellation.check()
                val count = source.read(buffer)
                if (count < 0) break
                maxRead = maxOf(maxRead, count)
                staged.write(buffer, 0, count)
            }
            cancellation.check()
            committed = staged.toByteArray()
            return "resource-id"
        }
    }

    private class Destination : DocumentNewDestination {
        var bytes = byteArrayOf()
        var claimed = false
        var deleted = false
        var aborted = false
        var failWrite = false
        var rejectRead = false
        var corruptReadback = false
        var deleteSucceeds = true
        var deleteThrows = false
        var maxWrite = 0
        override fun claim() = if (claimed) false else { claimed = true; true }
        override fun openWrite(cancellation: DocumentCancellation): DocumentWriteStream = object : DocumentWriteStream() {
            private val out = ByteArrayOutputStream()
            override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
            override fun write(data: ByteArray, offset: Int, length: Int) {
                if (failWrite) throw IOException("secret provider details")
                maxWrite = maxOf(maxWrite, length)
                out.write(data, offset, length)
            }
            override fun close() { bytes = out.toByteArray() }
            override fun abort() { aborted = true }
        }
        override fun openRead(cancellation: DocumentCancellation): InputStream {
            if (rejectRead) throw SecurityException("secret URI")
            return ByteArrayInputStream(if (corruptReadback) byteArrayOf(9) else bytes)
        }
        override fun delete(): Boolean {
            if (deleteThrows) throw SecurityException("secret URI")
            deleted = deleteSucceeds
            if (deleted) bytes = byteArrayOf()
            return deleted
        }
    }
}
