package com.deepstudent.services.resources.api

import java.io.InputStream
import java.io.OutputStream

/** The caller owns and closes both streams. Progress is cumulative bytes, not a percentage. */
data class StreamAttachmentRequest(val name: String, val mediaType: String, val expectedSize: Long? = null)

interface AttachmentStreams {
    /** An expected size, when provided, must match the complete stream, not just its prefix. */
    suspend fun createStream(
        request: StreamAttachmentRequest,
        input: InputStream,
        onProgress: (Long) -> Unit = {},
    ): AttachmentMetadata

    /** A failure can leave bytes in [output]; callers must stage their own external destination. */
    suspend fun copyTo(
        id: String,
        output: OutputStream,
        includeDeleted: Boolean = false,
        onProgress: (Long) -> Unit = {},
    ): AttachmentMetadata?
}
