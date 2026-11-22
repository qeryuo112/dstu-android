package com.deepstudent.app.storage

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.time.SystemMonotonicClock
import com.deepstudent.services.resources.api.*
import com.deepstudent.services.resources.backend.DocumentTransfer
import com.deepstudent.services.resources.data.saf.AndroidDocumentSelection
import com.deepstudent.services.resources.data.saf.DocumentSelectionFailure
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the production App storage and controlled document ports, not a cross-UID SAF fixture. */
@RunWith(AndroidJUnit4::class)
class DocumentResourceCompositionDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(base.cacheDir, "s2-07-composition-${System.nanoTime()}").apply { check(mkdirs()) }
    private val context = object : ContextWrapper(base) { override fun getFilesDir(): File = root }
    private val bytes = ByteArray(1536 * 1024 + 17) { (it % 251).toByte() }
    private val correlation = CorrelationId.parse("s207-composition-operation")
    private val request = StreamAttachmentRequest("fixture.bin", "application/octet-stream", bytes.size.toLong())

    @After fun cleanup() { check(root.deleteRecursively()) }

    @Test fun importReopensFromProductionStoreAndExportVerifiesReadback(): Unit = runBlocking {
        withContext(Dispatchers.IO) {
            val importProgress = mutableListOf<DocumentProgress>()
            val saved = MainStorageResources.open(context).use { storage ->
                val result = storage.documentTransfers.importAttachment(source(), request, DocumentCancellation(),
                    DocumentProgressObserver { importProgress.add(it) }) as DocumentImportResult.Stored
                assertEquals(digest(), result.digest)
                assertEquals(result.digest.sha256, result.resource.sha256)
                assertArrayEquals(bytes, storage.attachments.get(result.resource.id)?.bytes)
                result.resource
            }
            assertTrue(importProgress.any { it.phase == DocumentPhase.COPY && it.bytes == bytes.size.toLong() })
            MainStorageResources.open(context).use { storage ->
                assertArrayEquals(bytes, storage.attachments.get(saved.id)?.bytes)
                val target = DeviceTarget(correlation)
                val progress = mutableListOf<DocumentProgress>()
                val result = storage.documentTransfers.exportAttachment(saved.id, target, DocumentCancellation(),
                    DocumentProgressObserver { progress.add(it) })
                assertEquals(DocumentExportResult.Verified(digest()), result)
                assertArrayEquals(bytes, target.bytes)
                assertTrue(target.readBack)
                assertTrue(progress.any { it.phase == DocumentPhase.COPY && it.bytes == bytes.size.toLong() })
                assertTrue(progress.any { it.phase == DocumentPhase.VERIFY && it.bytes == bytes.size.toLong() })
            }
        }
    }

    @Test fun cancellationDuringCopyLeavesNoCommittedRowsOrVisibleBlob(): Unit = runBlocking {
        withContext(Dispatchers.IO) {
            val before = MainStorageResources.open(context).use { storage ->
                val initial = rowCounts(storage)
                val token = DocumentCancellation()
                val result = storage.documentTransfers.importAttachment(source(), request, token,
                    DocumentProgressObserver { token.cancel() })
                assertEquals(DocumentImportResult.Cancelled(DocumentPhase.COPY), result)
                assertEquals(initial, rowCounts(storage))
                assertNoPublishedBlob()
                initial
            }
            MainStorageResources.open(context).use { assertEquals(before, rowCounts(it)) }
        }
    }

    @Test fun cancellationAtSourceEofBeforeCommitLeavesNoRows(): Unit = runBlocking {
        withContext(Dispatchers.IO) {
            MainStorageResources.open(context).use { storage ->
                val before = rowCounts(storage)
                val token = DocumentCancellation()
                val source = object : DocumentSource {
                    override val correlationId = correlation
                    override fun open(cancellation: DocumentCancellation): InputStream =
                        object : ByteArrayInputStream(bytes) {
                            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                                val count = super.read(buffer, offset, length)
                                if (count < 0) token.cancel()
                                return count
                            }
                        }
                }
                val result = storage.documentTransfers.importAttachment(source, request, token)
                assertEquals(DocumentImportResult.Cancelled(DocumentPhase.COPY), result)
                assertEquals(before, rowCounts(storage))
                assertNoPublishedBlob()
            }
        }
    }

    @Test fun revocationDuringReadKeepsFailureClassificationAndDoesNotCommit(): Unit = runBlocking {
        withContext(Dispatchers.IO) {
            MainStorageResources.open(context).use { storage ->
                val before = rowCounts(storage)
                val revoked = object : DocumentSource {
                    override val correlationId = correlation
                    override fun open(cancellation: DocumentCancellation): InputStream =
                        object : ByteArrayInputStream(bytes) {
                            private var reads = 0
                            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                                if (reads++ > 0) throw SecurityException("private provider URI")
                                return super.read(buffer, offset, length)
                            }
                        }
                }
                val result = storage.documentTransfers.importAttachment(revoked, request, DocumentCancellation())
                assertEquals(DocumentImportResult.Failed(DocumentPhase.COPY, DocumentFailure.ACCESS_DENIED), result)
                assertEquals(before, rowCounts(storage))
                assertNoPublishedBlob()
            }
        }
    }

    @Test fun cancellationAfterActualStoreReturnsCommittedMetadataRemainsStored(): Unit = runBlocking {
        withContext(Dispatchers.IO) {
            val token = DocumentCancellation()
            val saved = MainStorageResources.open(context).use { storage ->
                val real = storage.attachments as AttachmentStreams
                val afterCommit = object : AttachmentStreams by real {
                    override suspend fun createStream(
                        request: StreamAttachmentRequest, input: InputStream, onProgress: (Long) -> Unit,
                    ): AttachmentMetadata = real.createStream(request, input, onProgress).also { token.cancel() }
                }
                val bridge = AttachmentDocumentTransfers(afterCommit, DocumentTransfer(noopLogger, SystemMonotonicClock))
                val result = bridge.importAttachment(source(), request, token)
                assertTrue(token.isCancelled)
                assertTrue(result is DocumentImportResult.Stored)
                (result as DocumentImportResult.Stored).resource
            }
            MainStorageResources.open(context).use { assertArrayEquals(bytes, it.attachments.get(saved.id)?.bytes) }
        }
    }

    @Test fun corruptProviderReadbackCleansDestinationNotProductionResource(): Unit = runBlocking {
        withContext(Dispatchers.IO) {
            MainStorageResources.open(context).use { storage ->
                val result = storage.documentTransfers.importAttachment(source(), request, DocumentCancellation())
                    as DocumentImportResult.Stored
                val target = DeviceTarget(correlation).apply { corruptRead = true }
                val exported = storage.documentTransfers.exportAttachment(result.resource.id, target, DocumentCancellation())
                assertEquals(DocumentExportResult.Failed(DocumentPhase.VERIFY, DocumentFailure.DIGEST_MISMATCH,
                    DocumentCleanup.DELETE_REPORTED), exported)
                assertTrue(target.deleted)
                assertArrayEquals(bytes, storage.attachments.get(result.resource.id)?.bytes)
            }
        }
    }

    @Test fun cancelExportDeletesDestinationAndKeepsProductionResource(): Unit = runBlocking {
        withContext(Dispatchers.IO) {
            MainStorageResources.open(context).use { storage ->
                val stored = storage.documentTransfers.importAttachment(source(), request, DocumentCancellation())
                    as DocumentImportResult.Stored
                val target = DeviceTarget(correlation)
                val token = DocumentCancellation()
                val result = storage.documentTransfers.exportAttachment(stored.resource.id, target, token,
                    DocumentProgressObserver { token.cancel() })
                assertEquals(DocumentExportResult.Cancelled(DocumentPhase.COPY, DocumentCleanup.DELETE_REPORTED), result)
                assertTrue(target.deleted)
                assertArrayEquals(bytes, storage.attachments.get(stored.resource.id)?.bytes)
            }
        }
    }

    @Test fun pickerFacadeUsesSystemContractsAndSingleUseCreateRequest(): Unit = runBlocking {
        withContext(Dispatchers.IO) {
            MainStorageResources.open(context).use { storage ->
                val open = storage.documentTransfers.openIntent("application/octet-stream")
                assertEquals(Intent.ACTION_OPEN_DOCUMENT, open.action)
                assertTrue(open.hasCategory(Intent.CATEGORY_OPENABLE))
                assertTrue(open.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                val request = storage.documentTransfers.createRequest("application/octet-stream", "fixture.bin")
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.action)
                assertTrue(request.intent.hasCategory(Intent.CATEGORY_OPENABLE))
                assertTrue(request.intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
                assertEquals(AndroidDocumentSelection.Cancelled, request.acceptResult(Activity.RESULT_CANCELED, null))
                assertEquals(AndroidDocumentSelection.Rejected(DocumentSelectionFailure.ALREADY_USED),
                    request.acceptResult(Activity.RESULT_CANCELED, null))
            }
        }
    }

    @Test fun selectedCorrelationReachesTransferLogWhileUsingProductionStore(): Unit = runBlocking {
        withContext(Dispatchers.IO) {
            MainStorageResources.open(context).use { storage ->
                val records = mutableListOf<LogRecord>()
                val logger = object : Logger { override fun log(record: LogRecord) { records.add(record) } }
                val bridge = AttachmentDocumentTransfers(storage.attachments as AttachmentStreams,
                    DocumentTransfer(logger, SystemMonotonicClock))
                val stored = bridge.importAttachment(source(), request, DocumentCancellation()) as DocumentImportResult.Stored
                val target = DeviceTarget(correlation)
                assertEquals(DocumentExportResult.Verified(digest()),
                    bridge.exportAttachment(stored.resource.id, target, DocumentCancellation()))
                assertTrue(records.size >= 4)
                records.forEach { record ->
                    assertEquals(correlation, record.correlationId)
                    assertNotNull(record.fields["owner"])
                    assertNotNull(record.fields["phase"])
                    assertNotNull(record.fields["state"])
                    assertNotNull(record.fields["elapsedMs"])
                }
                assertFalse(records.toString().contains(request.name))
            }
        }
    }

    private fun source(): DocumentSource = object : DocumentSource {
        override val correlationId = correlation
        override fun open(cancellation: DocumentCancellation): InputStream = ByteArrayInputStream(bytes)
    }
    private fun digest() = AssetDigest(Digests.sha256Hex(bytes), bytes.size.toLong())
    private fun rowCounts(storage: MainStorageResources): List<Long> = listOf("files", "resources", "blobs").map { table ->
        storage.databaseForSync().query("SELECT count(*) FROM $table") { it.long(0) }.single()
    }
    private fun assertNoPublishedBlob() {
        val files = root.resolve("active/vfs_blobs").walkTopDown().filter { it.isFile }.map { it.name }.toList()
        assertTrue(files.none { !it.startsWith('.') || it.startsWith(".prepare-") || it.startsWith(".pending-") })
    }
}

private val noopLogger = object : Logger { override fun log(record: LogRecord) = Unit }

private class DeviceTarget(override val correlationId: CorrelationId) : DocumentNewDestination {
    var bytes = byteArrayOf()
    var readBack = false
    var corruptRead = false
    var deleted = false
    private var claimed = false

    override fun claim(): Boolean = if (claimed) false else { claimed = true; true }
    override fun openWrite(cancellation: DocumentCancellation): DocumentWriteStream = object : DocumentWriteStream() {
        private val output = ByteArrayOutputStream()
        override fun write(value: Int) { output.write(value) }
        override fun write(data: ByteArray, offset: Int, length: Int) { output.write(data, offset, length) }
        override fun close() { bytes = output.toByteArray() }
        override fun abort() = Unit
    }
    override fun openRead(cancellation: DocumentCancellation): InputStream {
        readBack = true
        return ByteArrayInputStream(if (corruptRead) byteArrayOf(1) else bytes)
    }
    override fun delete(): Boolean { deleted = true; bytes = byteArrayOf(); return true }
}
