package com.deepstudent.services.resources.api

import com.deepstudent.core.common.id.ResourceId

/** Caller owns the enclosing VFS transaction and provides fresh identity and time. */
data class InlineNoteResource(
    val resourceId: ResourceId,
    val noteId: String,
    val content: String,
    val nowMillis: Long,
)

fun interface InlineNoteResourceWriter {
    fun put(value: InlineNoteResource): ResourceId
}

data class AttachmentMetadata(
    val id: String,
    val resourceId: ResourceId,
    val name: String,
    val mediaType: String,
    val storage: AttachmentStorage,
    val times: AttachmentTimes,
) {
    val size: Long get() = storage.size
    val sha256: String get() = storage.sha256
    val blobHash: String? get() = storage.blobHash
    val createdAt: String get() = times.createdAt
    val updatedAt: String get() = times.updatedAt
    val deletedAt: String? get() = times.deletedAt
}

data class AttachmentStorage(val size: Long, val sha256: String, val blobHash: String?)
data class AttachmentTimes(val createdAt: String, val updatedAt: String, val deletedAt: String?)

class AttachmentContent(val metadata: AttachmentMetadata, bytes: ByteArray) {
    private val snapshot = bytes.copyOf()
    val bytes: ByteArray get() = snapshot.copyOf()
}

class CreateAttachment(val name: String, val mediaType: String, bytes: ByteArray) {
    private val snapshot = bytes.copyOf()
    val bytes: ByteArray get() = snapshot.copyOf()
    val size: Int get() = snapshot.size
}

interface Attachments {
    suspend fun create(command: CreateAttachment): AttachmentMetadata
    suspend fun get(id: String, includeDeleted: Boolean = false): AttachmentContent?
    suspend fun resolve(resourceId: ResourceId): AttachmentMetadata?
    suspend fun rename(id: String, name: String): AttachmentMetadata
    suspend fun delete(id: String): AttachmentMetadata
    suspend fun restore(id: String): AttachmentMetadata
    suspend fun verify(id: String): AttachmentVerification
}

enum class AttachmentVerification {
    VALID,
    NOT_FOUND,
    DELETED,
    MISSING_RESOURCE,
    MISSING_BLOB,
    HASH_MISMATCH,
}
