package com.deepstudent.app.storage

import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.id.ResourceId
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.time.SystemMonotonicClock
import com.deepstudent.services.resources.api.*
import com.deepstudent.services.resources.backend.DocumentTransfer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DocumentResourceCompositionTest {
    private val bytes = ByteArray(128 * 1024 + 19) { (it % 251).toByte() }
    private val correlation = CorrelationId.parse("picker-operation-123")
    private val records = mutableListOf<LogRecord>()
    private val logger = object : Logger { override fun log(record: LogRecord) { records.add(record) } }
    private val store = RecordingStreams()
    private val transfers = AttachmentDocumentTransfers(store, DocumentTransfer(logger, SystemMonotonicClock))
    private val request = StreamAttachmentRequest("private-name.bin", "application/octet-stream", bytes.size.toLong())

    @Test fun sameStoreImportsAndExportsWithReadbackProgressAndCorrelation() = runBlocking {
        val importProgress = mutableListOf<DocumentProgress>()
        val imported = transfers.importAttachment(source(), request, DocumentCancellation(),
            DocumentProgressObserver { importProgress.add(it) }) as DocumentImportResult.Stored
        assertEquals(request, store.request)
        assertEquals(digest(bytes), imported.digest)
        assertEquals(imported.digest.sha256, imported.resource.sha256)
        assertArrayEquals(bytes, store.stored)
        assertTrue(importProgress.any { it.phase == DocumentPhase.COPY && it.bytes == bytes.size.toLong() })
        assertTrue(records.isNotEmpty())
        assertTrue(records.all { it.correlationId == correlation })

        records.clear()
        val target = Target(correlation)
        val exportProgress = mutableListOf<DocumentProgress>()
        val exported = transfers.exportAttachment(imported.resource.id, target, DocumentCancellation(),
            DocumentProgressObserver { exportProgress.add(it) })
        assertEquals(DocumentExportResult.Verified(digest(bytes)), exported)
        assertEquals(imported.resource.id, store.copiedId)
        assertArrayEquals(bytes, target.bytes)
        assertTrue(target.readBack)
        assertTrue(exportProgress.any { it.phase == DocumentPhase.VERIFY && it.bytes == bytes.size.toLong() })
        assertTrue(records.all { it.correlationId == correlation })
        assertFalse(records.toString().contains(request.name))
    }

    @Test fun cancelBeforeCommitDoesNotPublish() = runBlocking {
        val token = DocumentCancellation()
        val result = transfers.importAttachment(source(), request, token, DocumentProgressObserver { token.cancel() })
        assertEquals(DocumentImportResult.Cancelled(DocumentPhase.COPY), result)
        assertNull(store.stored)
    }

    @Test fun sourceRevocationRemainsAccessDeniedWhenStoreWrapsFailure() = runBlocking {
        store.wrapInputFailures = true
        val revoked = object : DocumentSource {
            override val correlationId = correlation
            override fun open(cancellation: DocumentCancellation): InputStream = object : InputStream() {
                override fun read(): Int = throw SecurityException("private provider URI")
                override fun read(data: ByteArray, offset: Int, length: Int): Int = read()
            }
        }
        val result = transfers.importAttachment(revoked, request, DocumentCancellation())
        assertEquals(DocumentImportResult.Failed(DocumentPhase.COPY, DocumentFailure.ACCESS_DENIED), result)
        assertNull(store.stored)
        assertFalse(records.toString().contains("private provider URI"))
    }

    @Test fun cancelAfterCommittedCreateDoesNotHideStoredResult() = runBlocking {
        val token = DocumentCancellation()
        store.afterCommit = { token.cancel() }
        val result = transfers.importAttachment(source(), request, token)
        assertTrue(token.isCancelled)
        assertTrue(result is DocumentImportResult.Stored)
        assertArrayEquals(bytes, store.stored)
    }

    @Test fun missingAttachmentDeletesOnlyFreshDestination() = runBlocking {
        val target = Target(correlation)
        val result = transfers.exportAttachment("missing", target, DocumentCancellation())
        assertEquals(DocumentExportResult.Failed(DocumentPhase.COPY, DocumentFailure.IO, DocumentCleanup.DELETE_REPORTED), result)
        assertTrue(target.aborted)
        assertTrue(target.deleted)
    }

    @Test fun wrongStoredDigestAbortsBeforeVerification() = runBlocking {
        store.stored = bytes
        store.wrongDigest = true
        val target = Target(correlation)
        val result = transfers.exportAttachment("id", target, DocumentCancellation())
        assertEquals(DocumentExportResult.Failed(DocumentPhase.COPY, DocumentFailure.DIGEST_MISMATCH,
            DocumentCleanup.DELETE_REPORTED), result)
        assertFalse(target.readBack)
        assertTrue(target.aborted)
    }

    @Test fun wrongReadbackNeverReportsVerifiedAndDeletesTarget() = runBlocking {
        store.stored = bytes
        val target = Target(correlation).apply { corruptRead = true }
        val result = transfers.exportAttachment("id", target, DocumentCancellation())
        assertEquals(DocumentExportResult.Failed(DocumentPhase.VERIFY, DocumentFailure.DIGEST_MISMATCH,
            DocumentCleanup.DELETE_REPORTED), result)
        assertTrue(target.readBack)
        assertTrue(target.deleted)
    }

    @Test fun cancelExportCleansTargetWithoutDeletingAttachment() = runBlocking {
        store.stored = bytes
        val target = Target(correlation)
        val token = DocumentCancellation()
        val result = transfers.exportAttachment("id", target, token, DocumentProgressObserver { token.cancel() })
        assertEquals(DocumentExportResult.Cancelled(DocumentPhase.COPY, DocumentCleanup.DELETE_REPORTED), result)
        assertTrue(target.deleted)
        assertArrayEquals(bytes, store.stored)
    }

    private fun source(): DocumentSource = object : DocumentSource {
        override val correlationId = correlation
        override fun open(cancellation: DocumentCancellation): InputStream = ByteArrayInputStream(bytes)
    }

    private fun digest(data: ByteArray) = AssetDigest(Digests.sha256Hex(data), data.size.toLong())
}

private class RecordingStreams : AttachmentStreams {
    var stored: ByteArray? = null
    var request: StreamAttachmentRequest? = null
    var copiedId: String? = null
    var wrongDigest = false
    var wrapInputFailures = false
    var afterCommit: () -> Unit = { }

    override suspend fun createStream(
        request: StreamAttachmentRequest, input: InputStream, onProgress: (Long) -> Unit,
    ): AttachmentMetadata {
        this.request = request
        val data = try { input.readBytes() } catch (failure: SecurityException) {
            if (wrapInputFailures) throw IllegalStateException("wrapped by store", failure) else throw failure
        }
        onProgress(data.size.toLong())
        stored = data
        return metadata(data).also { afterCommit() }
    }

    override suspend fun copyTo(
        id: String, output: OutputStream, includeDeleted: Boolean, onProgress: (Long) -> Unit,
    ): AttachmentMetadata? {
        copiedId = id
        check(!includeDeleted)
        val data = stored ?: return null
        output.write(data)
        onProgress(data.size.toLong())
        return metadata(if (wrongDigest) byteArrayOf(1) else data)
    }

    private fun metadata(data: ByteArray) = AttachmentMetadata(
        "id", ResourceId.parse("resource-id"), "private-name.bin", "application/octet-stream",
        AttachmentStorage(data.size.toLong(), Digests.sha256Hex(data), null), AttachmentTimes("a", "b", null),
    )
}

private class Target(override val correlationId: CorrelationId) : DocumentNewDestination {
    var bytes = byteArrayOf()
    var readBack = false
    var deleted = false
    var aborted = false
    var corruptRead = false
    private var claimed = false

    override fun claim(): Boolean = if (claimed) false else { claimed = true; true }
    override fun openWrite(cancellation: DocumentCancellation): DocumentWriteStream = object : DocumentWriteStream() {
        private val output = ByteArrayOutputStream()
        override fun write(value: Int) { output.write(value) }
        override fun write(data: ByteArray, offset: Int, length: Int) { output.write(data, offset, length) }
        override fun close() { bytes = output.toByteArray() }
        override fun abort() { aborted = true }
    }
    override fun openRead(cancellation: DocumentCancellation): InputStream {
        readBack = true
        return ByteArrayInputStream(if (corruptRead) byteArrayOf(0) else bytes)
    }
    override fun delete(): Boolean { deleted = true; bytes = byteArrayOf(); return true }
}
