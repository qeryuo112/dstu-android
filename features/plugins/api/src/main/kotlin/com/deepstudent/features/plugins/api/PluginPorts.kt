package com.deepstudent.features.plugins.api

import com.deepstudent.core.common.json.JsonValue

data class PluginPutReceipt(
    val namespace: PluginNamespace,
    val recordId: PluginRecordId,
    val localVersion: Long,
    val updatedAt: String,
)

data class PluginRemoveReceipt(
    val deleted: Boolean,
    val updatedAt: String?,
)

/** Narrow data store contract. Installation and trust are deliberately absent. */
interface PluginDataRepository {
    suspend fun read(
        ownerId: PluginOwnerId,
        namespace: PluginNamespace,
        recordId: PluginRecordId,
    ): PluginRecord?

    suspend fun put(
        capability: PluginCapability,
        namespace: PluginNamespace,
        recordId: PluginRecordId,
        payload: JsonValue,
        minWriterSchema: Int = 1,
    ): PluginPutReceipt

    suspend fun remove(
        ownerId: PluginOwnerId,
        namespace: PluginNamespace,
        recordId: PluginRecordId,
    ): PluginRemoveReceipt

    suspend fun quota(ownerId: PluginOwnerId): PluginOwnerUsage
}
