package com.deepstudent.services.backup.backend

import java.util.Collections
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

private val TABLES = setOf("instanceBinding", "uploadSeq", "consumeCursor", "tombstoneWatermark", "tombstoneEventPublish", "legacyProcessedKey", "deviceHistory")

internal fun parseContinuityState(
    root: JsonObject, repository: ContinuityRepository, writer: String, checkCancelled: () -> Unit,
): ContinuityState {
    root.shape(TABLES)
    val rows = TABLES.associateWith(root::arrayValue)
    continuityRequire(rows.values.sumOf { it.size.toLong() } <= CONTINUITY_MAX_ROWS, ContinuityFailure.TOO_LARGE)
    continuityRequire(rows.getValue("deviceHistory").isEmpty(), ContinuityFailure.INVALID_STATE)
    val binding = rows.getValue("instanceBinding")
    continuityRequire(binding.size == 1, ContinuityFailure.INVALID_STATE)
    val repo = binding.single().row()
    repo.shape(setOf("instance_id", "provider", "endpoint_hint"))
    continuityRequire(repo.text("instance_id") == repository.instanceId && repo.text("provider") == repository.provider && repo.text("endpoint_hint") == repository.endpointHint, ContinuityFailure.TRUST_MISMATCH)
    val uploads = rows.getValue("uploadSeq")
    continuityRequire(uploads.size == 1, ContinuityFailure.INVALID_STATE)
    val upload = uploads.single().row()
    upload.shape(setOf("instance_id", "device_id", "last_seq"))
    upload.matchInstance(repository.instanceId)
    continuityRequire(upload.text("device_id") == writer, ContinuityFailure.INVALID_STATE)
    val cursors = rows.getValue("consumeCursor").parseRows(checkCancelled) { cursor(it, repository.instanceId) }
    val watermarks = rows.getValue("tombstoneWatermark").parseRows(checkCancelled) { watermark(it, repository.instanceId) }
    val publications = rows.getValue("tombstoneEventPublish").parseRows(checkCancelled) { publication(it, repository.instanceId, writer) }
    val keys = rows.getValue("legacyProcessedKey").parseRows(checkCancelled) { processedKey(it, repository.instanceId) }
    requireDistinct(cursors.map { it.uploaderDeviceId })
    requireDistinct(watermarks.map { it.sourceDeviceId to it.kind })
    requireDistinct(publications.map { it.kind to it.operationId })
    requireDistinct(publications.map { it.kind to it.seq })
    requireDistinct(keys)
    return ContinuityState(repository, ContinuityUpload(writer, upload.integer("last_seq", true)), immutable(cursors), immutable(watermarks), immutable(publications), immutable(keys))
}

private fun cursor(row: JsonObject, instance: String): ContinuityCursor {
    row.shape(setOf("instance_id", "uploader_device_id", "last_seq")); row.matchInstance(instance)
    return ContinuityCursor(safeIdentity(row.text("uploader_device_id")), row.integer("last_seq", true))
}
private fun watermark(row: JsonObject, instance: String): ContinuityWatermark {
    row.shape(setOf("instance_id", "source_device_id", "kind", "last_applied_offset")); row.matchInstance(instance)
    return ContinuityWatermark(safeIdentity(row.text("source_device_id")), row.text("kind"), row.integer("last_applied_offset", true))
}
private fun publication(row: JsonObject, instance: String, writer: String): ContinuityPublication {
    row.shape(setOf("instance_id", "device_id", "kind", "operation_id", "seq")); row.matchInstance(instance)
    continuityRequire(row.text("device_id") == writer, ContinuityFailure.INVALID_STATE)
    return ContinuityPublication(writer, row.text("kind"), row.text("operation_id"), row.integer("seq", true, positive = true))
}
private fun processedKey(row: JsonObject, instance: String): String {
    row.shape(setOf("instance_id", "object_key")); row.matchInstance(instance)
    return row.text("object_key")
}
private fun JsonObject.matchInstance(expected: String) {
    continuityRequire(text("instance_id") == expected, ContinuityFailure.INVALID_STATE)
}
private fun <T> JsonArray.parseRows(checkCancelled: () -> Unit, parse: (JsonObject) -> T): List<T> = mapIndexed { index, item ->
    if (index % 128 == 0) checkCancelled()
    parse(item.row())
}
private fun <T> requireDistinct(values: List<T>) {
    continuityRequire(values.toSet().size == values.size, ContinuityFailure.INVALID_STATE)
}
private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
