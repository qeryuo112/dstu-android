package com.deepstudent.app.storage

import com.deepstudent.services.resources.api.AssetDigest
import com.deepstudent.services.resources.api.AttachmentMetadata
import com.deepstudent.services.resources.api.AttachmentStreams
import com.deepstudent.services.resources.api.DocumentCancellation
import com.deepstudent.services.resources.api.DocumentExportResult
import com.deepstudent.services.resources.api.DocumentExportSource
import com.deepstudent.services.resources.api.DocumentImportResult
import com.deepstudent.services.resources.api.DocumentImportWriter
import com.deepstudent.services.resources.api.DocumentNewDestination
import com.deepstudent.services.resources.api.DocumentProgressObserver
import com.deepstudent.services.resources.api.DocumentSource
import com.deepstudent.services.resources.api.StreamAttachmentRequest
import com.deepstudent.services.resources.backend.DocumentTransfer
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** Bridges the one production attachment store to document streams; it owns no UI lifecycle. */
internal class AttachmentDocumentTransfers(
    private val attachments: AttachmentStreams,
    private val transfer: DocumentTransfer,
) {
    /** Call from a worker: success means the attachment has already crossed its commit boundary. */
    suspend fun importAttachment(
        source: DocumentSource,
        request: StreamAttachmentRequest,
        cancellation: DocumentCancellation,
        progress: DocumentProgressObserver = DocumentProgressObserver { },
    ): DocumentImportResult<AttachmentMetadata> = transfer.import(
        source,
        DocumentImportWriter { input, token ->
            token.check()
            val tracked = SourceFailureInput(input)
            try {
                // No check after return: cancelling after commit must not hide the stored result.
                attachments.createStream(request, tracked) { token.check() }
            } catch (failure: Exception) {
                // The store wraps source IO/permission errors; retain their transfer classification.
                throw tracked.failure ?: failure
            }
        },
        cancellation,
        progress,
    )

    /** The store copies and checks its bytes; DocumentTransfer also hashes and reads the target back. */
    suspend fun exportAttachment(
        id: String,
        destination: DocumentNewDestination,
        cancellation: DocumentCancellation,
        progress: DocumentProgressObserver = DocumentProgressObserver { },
    ): DocumentExportResult = transfer.export(
        DocumentExportSource { output, token ->
            token.check()
            val metadata = attachments.copyTo(id, output, includeDeleted = false) { token.check() }
                ?: throw FileNotFoundException("attachment unavailable")
            AssetDigest(metadata.sha256, metadata.size)
        },
        destination,
        cancellation,
        progress,
    )
}

/** Preserve only the failure type for the transfer; the exception message is never logged. */
private class SourceFailureInput(input: InputStream) : FilterInputStream(input) {
    var failure: Exception? = null
        private set

    override fun read(): Int = capture { super.read() }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int = capture { super.read(bytes, offset, length) }

    private inline fun <T> capture(action: () -> T): T = try {
        action()
    } catch (caught: Exception) {
        if (caught is IOException || caught is SecurityException) failure = caught
        throw caught
    }
}
