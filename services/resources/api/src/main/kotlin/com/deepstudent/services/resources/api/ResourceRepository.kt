package com.deepstudent.services.resources.api

import com.deepstudent.core.common.id.ResourceId
import com.deepstudent.core.common.json.JsonValue

data class ResourceMetadata(
    val id: ResourceId,
    val digest: AssetDigest?,
    val mediaType: String? = null,
    val metadata: JsonValue.Object = JsonValue.Object.EMPTY,
    val deletedAt: String? = null,
)

data class ResourceContent(
    val metadata: ResourceMetadata,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is ResourceContent && metadata == other.metadata && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * metadata.hashCode() + bytes.contentHashCode()
}

data class ResourcePutRequest(
    val id: ResourceId,
    val bytes: ByteArray,
    val mediaType: String? = null,
    val metadata: JsonValue.Object = JsonValue.Object.EMPTY,
)

sealed interface ResourcePutResult {
    data class Stored(val metadata: ResourceMetadata) : ResourcePutResult

    data class Conflict(val id: ResourceId, val message: String) : ResourcePutResult
}

/** Narrow read/write contract owned by the resources service. */
interface ResourceRepository {
    suspend fun stat(id: ResourceId): ResourceMetadata?

    suspend fun read(id: ResourceId): ResourceContent?

    suspend fun put(request: ResourcePutRequest): ResourcePutResult
}
