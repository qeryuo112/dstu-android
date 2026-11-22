package com.deepstudent.services.resources.backend

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.id.ResourceId
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.core.common.time.MonotonicClock
import com.deepstudent.core.common.time.SystemMonotonicClock
import com.deepstudent.services.resources.api.AttachmentContent
import com.deepstudent.services.resources.api.AttachmentMetadata
import com.deepstudent.services.resources.api.AttachmentStreams
import com.deepstudent.services.resources.api.StreamAttachmentRequest
import java.io.InputStream
import java.io.OutputStream
import com.deepstudent.services.resources.api.AttachmentVerification
import com.deepstudent.services.resources.api.Attachments
import com.deepstudent.services.resources.api.CreateAttachment
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class AttachmentService(
    private val repository: AttachmentRepository,
    logger: Logger = NoopLogger,
    monotonicClock: MonotonicClock = SystemMonotonicClock,
) : Attachments, AttachmentStreams {
    private val diagnostics = ResourceDiagnostics(logger, monotonicClock)

    override suspend fun create(command: CreateAttachment): AttachmentMetadata = diagnostics.record("create") {
        currentCoroutineContext().ensureActive()
        validate(command.name, command.mediaType, command.size.toLong())
        repository.create(command)
    }

    override suspend fun createStream(
        request: StreamAttachmentRequest, input: InputStream, onProgress: (Long) -> Unit,
    ): AttachmentMetadata = diagnostics.record("createStream") {
        currentCoroutineContext().ensureActive()
        validate(request.name, request.mediaType, request.expectedSize)
        repository.createStream(request, input, onProgress)
    }

    override suspend fun copyTo(
        id: String, output: OutputStream, includeDeleted: Boolean, onProgress: (Long) -> Unit,
    ): AttachmentMetadata? = diagnostics.record("copyTo") {
        currentCoroutineContext().ensureActive()
        repository.copyTo(id, output, includeDeleted, onProgress)
    }

    override suspend fun get(id: String, includeDeleted: Boolean): AttachmentContent? = diagnostics.record("get") {
        repository.get(id, includeDeleted)
    }

    override suspend fun resolve(resourceId: ResourceId): AttachmentMetadata? = diagnostics.record("resolve") {
        repository.resolve(resourceId)
    }

    override suspend fun rename(id: String, name: String): AttachmentMetadata = diagnostics.record("rename") {
        currentCoroutineContext().ensureActive()
        validateName(name)
        repository.rename(id, name)
    }

    override suspend fun delete(id: String): AttachmentMetadata = diagnostics.record("delete") { repository.delete(id) }

    override suspend fun restore(id: String): AttachmentMetadata = diagnostics.record("restore") { repository.restore(id) }

    override suspend fun verify(id: String): AttachmentVerification = diagnostics.record("verify") { repository.verify(id) }

    private fun validate(name: String, mediaType: String, size: Long?) {
        validateName(name)
        if (mediaType.isBlank() || mediaType.length > 255) invalid()
        val max = if (mediaType.startsWith("image/", ignoreCase = true)) IMAGE_LIMIT else FILE_LIMIT
        if (size != null && (size < 0 || size > max)) invalid()
    }

    private fun validateName(name: String) {
        if (name.isBlank() || name.length > 1024 || '\u0000' in name) invalid()
    }

    private fun invalid(): Nothing =
        throw DstuException(DstuError.validation("INVALID_ATTACHMENT", "invalid attachment input"))

    companion object {
        const val IMAGE_LIMIT = 50 * 1024 * 1024
        const val FILE_LIMIT = 200 * 1024 * 1024
        const val INLINE_LIMIT = 1024 * 1024
    }
}

interface AttachmentRepository : Attachments, AttachmentStreams {
    override suspend fun createStream(
        request: StreamAttachmentRequest, input: InputStream, onProgress: (Long) -> Unit,
    ): AttachmentMetadata = throw UnsupportedOperationException("streaming not implemented")

    override suspend fun copyTo(
        id: String, output: OutputStream, includeDeleted: Boolean, onProgress: (Long) -> Unit,
    ): AttachmentMetadata? = throw UnsupportedOperationException("streaming not implemented")
}
