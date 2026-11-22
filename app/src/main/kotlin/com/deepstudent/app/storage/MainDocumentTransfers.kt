package com.deepstudent.app.storage

import android.content.Context
import android.content.Intent
import com.deepstudent.core.common.time.SystemMonotonicClock
import com.deepstudent.services.resources.api.AttachmentMetadata
import com.deepstudent.services.resources.api.AttachmentStreams
import com.deepstudent.services.resources.api.DocumentCancellation
import com.deepstudent.services.resources.api.DocumentExportResult
import com.deepstudent.services.resources.api.DocumentImportResult
import com.deepstudent.services.resources.api.DocumentNewDestination
import com.deepstudent.services.resources.api.DocumentProgressObserver
import com.deepstudent.services.resources.api.DocumentSource
import com.deepstudent.services.resources.api.StreamAttachmentRequest
import com.deepstudent.services.resources.backend.DocumentTransfer
import com.deepstudent.services.resources.data.saf.AndroidCreateDocumentRequest
import com.deepstudent.services.resources.data.saf.AndroidDocumentPicker
import com.deepstudent.services.resources.data.saf.AndroidDocumentSelection

/**
 * Main-process callable ports, not an Activity or a UI. The caller launches the system picker,
 * passes its unmodified matching result, and owns the transfer token independently of its screen.
 * Transfers perform blocking stream I/O: start them on a worker and keep the storage open until done.
 */
internal class MainDocumentTransfers(context: Context, attachments: AttachmentStreams) {
    private val picker = AndroidDocumentPicker(context, AndroidOperationLogger, SystemMonotonicClock)
    private val transfers = AttachmentDocumentTransfers(
        attachments, DocumentTransfer(AndroidOperationLogger, SystemMonotonicClock),
    )

    fun openIntent(mimeType: String): Intent = picker.openIntent(mimeType)

    fun acceptOpen(resultCode: Int, data: Intent?, persist: Boolean = true): AndroidDocumentSelection =
        picker.acceptOpen(resultCode, data, persist)

    /** Keep the returned one-shot request until the matching system result is delivered. */
    fun createRequest(mimeType: String, displayName: String): AndroidCreateDocumentRequest =
        picker.createRequest(mimeType, displayName)

    /** References are sensitive; persist privately and never write them to logs. */
    fun restoreRead(reference: String): AndroidDocumentSelection = picker.restoreRead(reference)

    fun releasePersistedRead(reference: String): Boolean = picker.releasePersistedRead(reference)

    suspend fun importAttachment(
        source: DocumentSource,
        request: StreamAttachmentRequest,
        cancellation: DocumentCancellation,
        progress: DocumentProgressObserver = DocumentProgressObserver { },
    ): DocumentImportResult<AttachmentMetadata> = transfers.importAttachment(source, request, cancellation, progress)

    suspend fun exportAttachment(
        id: String,
        destination: DocumentNewDestination,
        cancellation: DocumentCancellation,
        progress: DocumentProgressObserver = DocumentProgressObserver { },
    ): DocumentExportResult = transfers.exportAttachment(id, destination, cancellation, progress)
}
