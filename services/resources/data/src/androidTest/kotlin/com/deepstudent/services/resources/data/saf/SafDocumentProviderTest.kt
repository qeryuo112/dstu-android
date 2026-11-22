package com.deepstudent.services.resources.data.saf

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.time.SystemMonotonicClock
import com.deepstudent.services.resources.api.*
import com.deepstudent.services.resources.backend.DocumentTransfer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Same-UID fixture: verifies result shape and PFD behavior, not Android URI grant enforcement. */
@RunWith(AndroidJUnit4::class)
class SafDocumentProviderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val records = mutableListOf<LogRecord>()
    private val logger = object : Logger {
        override fun log(record: LogRecord) {
            synchronized(records) { records.add(record) }
            Log.i("DstuSafTest", "event=${record.event} correlationId=${record.correlationId?.value} fields=${record.fields}")
        }
    }
    private val picker = AndroidDocumentPicker(context, logger, SystemMonotonicClock)
    private val transfer = DocumentTransfer(logger, SystemMonotonicClock)
    private val created = mutableListOf<Pair<String, Uri>>()
    private val root = DocumentsContract.buildDocumentUri(SafFixture.AUTHORITY, "root")

    @After fun cleanup() {
        created.forEach { (id, uri) ->
            SafFixture.entries[id]?.gate?.countDown()
            try { DocumentsContract.deleteDocument(resolver, uri) } catch (_: Exception) { }
            SafFixture.entries.remove(id)
        }
    }

    @Test fun pickerIntentsUseSystemDocumentActions() {
        val open = picker.openIntent("application/pdf")
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, open.action)
        assertTrue(open.hasCategory(Intent.CATEGORY_OPENABLE))
        assertTrue(open.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val create = picker.createRequest("application/pdf", "result.pdf").intent
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, create.action)
        assertTrue(create.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals(RW, create.flags and RW)
    }

    @Test fun pickerRejectsNonContentAndMissingReadFlag() {
        assertEquals(AndroidDocumentSelection.Rejected(DocumentSelectionFailure.INVALID_URI),
            picker.acceptOpen(Activity.RESULT_OK, Intent().setData(Uri.parse("file:///synthetic-fixture")), false))
        val (_, uri) = newDocument("local")
        assertEquals(AndroidDocumentSelection.Rejected(DocumentSelectionFailure.ACCESS_DENIED),
            picker.acceptOpen(Activity.RESULT_OK, Intent().setData(uri), false))
    }

    @Test fun createdDocumentWithWriteOnlyFlagIsRejected() {
        val (_, uri) = newDocument("local")
        val data = Intent().setData(uri).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val rejected = picker.createRequest("application/octet-stream", "fixture").acceptResult(
            Activity.RESULT_OK, data, false,
        ) as AndroidDocumentSelection.Rejected
        assertEquals(DocumentSelectionFailure.ACCESS_DENIED, rejected.failure)
        // This is only a result flag check; actual grant-gated cleanup is tested across UIDs.
    }

    @Test fun rejectedCreateWithoutWriteFlagReportsPossibleLeftover() {
        val (id, uri) = newDocument("local")
        val result = picker.createRequest("application/octet-stream", "fixture").acceptResult(
            Activity.RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), false,
        )
        assertEquals(AndroidDocumentSelection.Rejected(DocumentSelectionFailure.ACCESS_DENIED, DocumentCleanup.MAY_REMAIN), result)
        assertTrue(SafFixture.entries.containsKey(id))
    }

    @Test fun requestAndDestinationAreOneShot() = runBlocking {
        val (_, uri) = newDocument("local")
        val request = picker.createRequest("application/octet-stream", "one-shot")
        assertEquals(AndroidDocumentSelection.Cancelled, request.acceptResult(Activity.RESULT_CANCELED, null))
        assertEquals(AndroidDocumentSelection.Rejected(DocumentSelectionFailure.ALREADY_USED),
            request.acceptResult(Activity.RESULT_CANCELED, null))
        val target = destination(uri)
        val content = pattern(32768)
        assertEquals(DocumentExportResult.Verified(content.digest), transfer.export(content.source, target, DocumentCancellation()))
        assertEquals(DocumentExportResult.Failed(DocumentPhase.OPEN_DESTINATION, DocumentFailure.INVALID_TARGET, DocumentCleanup.NOT_ATTEMPTED),
            transfer.export(content.source, target, DocumentCancellation()))
    }

    @Test fun localProviderLargeFileVerified() = runBlocking {
        val (_, uri) = newDocument("local")
        val content = pattern(4 * 1024 * 1024 + 31)
        assertEquals(DocumentExportResult.Verified(content.digest),
            transfer.export(content.source, destination(uri), DocumentCancellation()))
        val verified = transfer.import(source(uri), countingWriter(), DocumentCancellation())
        assertEquals(DocumentImportResult.Stored(content.digest, content.digest), verified)
    }

    @Test fun httpCloudPipeRoundTripLargeFileAndHash() = runBlocking {
        val (_, uri) = newDocument("cloud")
        val content = pattern(16 * 1024 * 1024 + 37)
        val progress = mutableListOf<DocumentProgress>()
        val exported = transfer.export(content.source, destination(uri), DocumentCancellation(),
            DocumentProgressObserver { progress.add(it) })
        assertEquals(DocumentExportResult.Verified(content.digest), exported)
        assertTrue(progress.any { it.phase == DocumentPhase.COPY && it.bytes == content.digest.size })
        assertTrue(progress.any { it.phase == DocumentPhase.VERIFY && it.bytes == content.digest.size })
        assertEquals(DocumentImportResult.Stored(content.digest, content.digest),
            transfer.import(source(uri), countingWriter(), DocumentCancellation()))
    }

    @Test fun httpProviderReturnsNonSeekablePfd() {
        val (_, uri) = newDocument("cloud")
        resolver.openFileDescriptor(uri, "w")!!.use { pfd ->
            try { Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_SET); fail("pipe unexpectedly seekable") }
            catch (expected: ErrnoException) { assertEquals(OsConstants.ESPIPE, expected.errno) }
        }
    }

    @Test fun failedHttpPutReportsFailureAndDeletesTarget() = runBlocking {
        val (_, uri) = newDocument("fail-put")
        val result = transfer.export(pattern(1024 * 1024).source, destination(uri), DocumentCancellation())
        assertTrue(result is DocumentExportResult.Failed)
        assertEquals(DocumentCleanup.DELETE_REPORTED, (result as DocumentExportResult.Failed).cleanup)
    }

    @Test fun missingHttpObjectIsImportFailureNotCommitted() = runBlocking {
        val (_, uri) = newDocument("missing")
        var committed = false
        val result = transfer.import(source(uri), DocumentImportWriter { input, _ ->
            while (input.read() >= 0) { }
            committed = true
            "unused"
        }, DocumentCancellation())
        assertTrue(result is DocumentImportResult.Failed)
        assertFalse(committed)
    }

    @Test fun readPipeCancellationInterruptsActualBlockedDescriptor() = runBlocking {
        val (id, uri) = newDocument("stall-read")
        val token = DocumentCancellation()
        val operation = async(Dispatchers.IO) { transfer.import(source(uri), countingWriter(), token) }
        assertTrue(SafFixture.entry(id).waiting.await(5, TimeUnit.SECONDS))
        token.cancel()
        val result = withTimeout(5_000) { operation.await() }
        assertTrue(result is DocumentImportResult.Cancelled)
        SafFixture.entry(id).gate.countDown()
    }

    @Test fun writePipeCancellationInterruptsActualBlockedDescriptorAndCleans() = runBlocking {
        val (id, uri) = newDocument("stall-write")
        val token = DocumentCancellation()
        val content = pattern(16 * 1024 * 1024)
        val operation = async(Dispatchers.IO) { transfer.export(content.source, destination(uri), token) }
        assertTrue(SafFixture.entry(id).waiting.await(5, TimeUnit.SECONDS))
        // Give the non-reading pipe time to fill before cancellation.
        Thread.sleep(300)
        token.cancel()
        val result = withTimeout(10_000) { operation.await() }
        assertEquals(DocumentExportResult.Cancelled(DocumentPhase.COPY, DocumentCleanup.DELETE_REPORTED), result)
    }

    @Test fun cancellationBeforeStartDeletesCreatedDocument() = runBlocking {
        val (id, uri) = newDocument("local")
        val entry = SafFixture.entry(id)
        val result = transfer.export(pattern(10).source, destination(uri), DocumentCancellation().apply { cancel() })
        assertEquals(DocumentExportResult.Cancelled(DocumentPhase.OPEN_DESTINATION, DocumentCleanup.DELETE_REPORTED), result)
        assertEquals(1, entry.deleteAttempts.get())
        assertFalse(SafFixture.entries.containsKey(id))
    }

    @Test fun providerRefusalMakesPartialStateExplicit() = runBlocking {
        val (id, uri) = newDocument("deny-delete")
        val entry = SafFixture.entry(id)
        val result = transfer.export(pattern(10).source, destination(uri), DocumentCancellation().apply { cancel() })
        assertEquals(DocumentExportResult.Cancelled(DocumentPhase.OPEN_DESTINATION, DocumentCleanup.MAY_REMAIN), result)
        assertEquals("provider refusal, not a failed grant check", 1, entry.deleteAttempts.get())
        assertTrue(SafFixture.entries.containsKey(id))
    }

    @Test fun localProviderPfdIsSeekableAndReadsWrittenBytes() {
        val (_, uri) = newDocument("local")
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        ParcelFileDescriptor.AutoCloseOutputStream(requireNotNull(resolver.openFileDescriptor(uri, "w"))).use {
            it.write(bytes)
        }
        requireNotNull(resolver.openFileDescriptor(uri, "r")).use { pfd ->
            assertEquals(bytes.size.toLong(), pfd.statSize)
            assertEquals(4L, Os.lseek(pfd.fileDescriptor, 4, OsConstants.SEEK_SET))
            val lastByte = ByteArray(1)
            assertEquals(1, Os.read(pfd.fileDescriptor, lastByte, 0, lastByte.size))
            assertEquals(bytes.last(), lastByte.single())
        }
    }

    @Test fun cancelledCopyCleansDestinationAndFreshTargetCanResume() = runBlocking {
        val (_, original) = newDocument("local")
        val existing = pattern(65537)
        assertTrue(transfer.export(existing.source, destination(original), DocumentCancellation()) is DocumentExportResult.Verified)
        val (partialId, partial) = newDocument("local")
        val token = DocumentCancellation()
        val result = transfer.export(pattern(1024 * 1024).source, destination(partial), token, DocumentProgressObserver {
            if (it.phase == DocumentPhase.COPY) token.cancel()
        })
        assertEquals(DocumentExportResult.Cancelled(DocumentPhase.COPY, DocumentCleanup.DELETE_REPORTED), result)
        assertFalse(SafFixture.entries.containsKey(partialId))
        assertEquals(DocumentImportResult.Stored(existing.digest, existing.digest),
            transfer.import(source(original), countingWriter(), DocumentCancellation()))
        val (_, fresh) = newDocument("cloud")
        val content = pattern(1024 * 1024)
        assertEquals(DocumentExportResult.Verified(content.digest), transfer.export(content.source, destination(fresh), DocumentCancellation()))
        assertEquals(DocumentImportResult.Stored(content.digest, content.digest),
            transfer.import(source(fresh), countingWriter(), DocumentCancellation()))
    }

    @Test fun pickerAndTransferLogsContainOnlyRedactedStructuredFields() = runBlocking {
        val (_, uri) = newDocument("local")
        val content = pattern(100_000)
        val target = destination(uri)
        assertEquals(AndroidDocumentSelection.Rejected(DocumentSelectionFailure.ACCESS_DENIED),
            picker.acceptOpen(Activity.RESULT_OK, Intent().setData(uri), false))
        assertEquals(DocumentExportResult.Verified(content.digest), transfer.export(content.source, target, DocumentCancellation()))
        val grant = records.single { it.event == "resources.document.grant" }
        assertNotEquals(target.correlationId, grant.correlationId)
        assertEquals(setOf(target.correlationId), records.filter { it.event == "resources.document.transfer" }
            .map { it.correlationId }.toSet())
        records.forEach { record ->
            assertNotNull(record.correlationId)
            assertNotNull(record.fields["owner"])
            assertNotNull(record.fields["phase"])
            assertNotNull(record.fields["state"])
            assertNotNull(record.fields["elapsedMs"])
            assertFalse(record.fields.toString().contains(uri.toString()))
            assertFalse(record.fields.toString().contains(SafFixture.AUTHORITY))
        }
    }

    private fun newDocument(prefix: String): Pair<String, Uri> {
        val id = "$prefix-${UUID.randomUUID()}"
        val uri = requireNotNull(DocumentsContract.createDocument(resolver, root, "application/octet-stream", id))
        created.add(id to uri)
        return id to uri
    }

    // Exercise the production descriptor and transfer code directly under this provider's UID.
    // Never manufacture a picker success or a self-grant for the content/HTTP tests.
    private fun source(uri: Uri) = DocumentSource { cancellation -> openSafInput(resolver, uri, cancellation) }
    private fun destination(uri: Uri): DocumentNewDestination = object : DocumentNewDestination {
        override val correlationId = CorrelationId.parse(UUID.randomUUID().toString())
        private val claimed = AtomicBoolean(false)
        override fun claim() = claimed.compareAndSet(false, true)
        override fun openWrite(cancellation: DocumentCancellation) = openSafOutput(resolver, uri, cancellation)
        override fun openRead(cancellation: DocumentCancellation) = openSafInput(resolver, uri, cancellation)
        override fun delete() = DocumentsContract.deleteDocument(resolver, uri)
    }

    private fun countingWriter() = DocumentImportWriter { input, cancellation ->
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        var size = 0L
        while (true) {
            cancellation.check()
            val count = input.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
            size += count
        }
        AssetDigest(Digests.toHex(hash.digest()), size)
    }

    private fun pattern(size: Int): Pattern {
        val block = ByteArray(16 * 1024) { (it % 251).toByte() }
        val digest = MessageDigest.getInstance("SHA-256")
        var left = size
        while (left > 0) {
            val n = minOf(left, block.size)
            digest.update(block, 0, n)
            left -= n
        }
        val expected = AssetDigest(Digests.toHex(digest.digest()), size.toLong())
        return Pattern(expected, DocumentExportSource { output, cancellation ->
            var remain = size
            while (remain > 0) {
                cancellation.check()
                val n = minOf(remain, block.size)
                output.write(block, 0, n)
                remain -= n
            }
            expected
        })
    }

    private data class Pattern(val digest: AssetDigest, val source: DocumentExportSource)

    companion object {
        private const val RW = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
