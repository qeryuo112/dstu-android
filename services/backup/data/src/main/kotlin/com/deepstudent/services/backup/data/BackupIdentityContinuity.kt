package com.deepstudent.services.backup.data

import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.backup.backend.ContinuityValidation

private fun text(value: String) = SqlValue.Text(value)
private fun number(value: Long) = SqlValue.Integer(value)

internal fun SqlOperations.backupIdentityApplyContinuity(matched: ContinuityValidation.Matched, aliases: BackupIdentityPeerAliases) {
    val state = matched.state
    val instance = state.repository.instanceId
    val writer = state.upload.deviceId
    val key = matched.takeoverKey
    val exists = query("SELECT 1 FROM __dstu_identity_receipt WHERE takeover_key=?", listOf(text(key))) { it.long(0) }.isNotEmpty()
    val legacyExists = query("SELECT 1 FROM __dstu_identity_legacy_key WHERE instance_id=? AND object_key=?", listOf(text(instance), text(key))) { it.long(0) }.isNotEmpty()
    if (exists || legacyExists) throw BackupIdentityException(BackupIdentityFailure.DUPLICATE_TAKEOVER)
    backupIdentityCheckBinding(instance, state.repository.provider, state.repository.endpointHint)
    backupIdentityCheckPending(instance, writer)
    backupIdentityCheckSequences(matched)
    val runtimeCursors = backupIdentityCursorAliases(matched, aliases)
    execute("INSERT INTO __dstu_identity_binding(instance_id,provider,endpoint_hint) VALUES(?,?,?) ON CONFLICT(instance_id) DO NOTHING",
        listOf(text(instance), text(state.repository.provider), text(state.repository.endpointHint)))
    execute("INSERT INTO sync_upload_state(instance,device,seq) VALUES(?,?,?) ON CONFLICT(instance,device) DO UPDATE SET seq=max(seq,excluded.seq)",
        listOf(text(instance), text(writer), number(state.upload.lastSeq)))
    execute("DELETE FROM __android_sync_cursor_v2 WHERE instance=?", listOf(text(instance)))
    execute("DELETE FROM __dstu_identity_source_cursor WHERE instance_id=?", listOf(text(instance)))
    for (cursor in state.cursors) {
        execute("INSERT INTO __dstu_identity_source_cursor(instance_id,uploader_device_id,last_seq) VALUES(?,?,?)",
            listOf(text(instance), text(cursor.uploaderDeviceId), number(cursor.lastSeq)))
    }
    for ((device, sequence) in runtimeCursors) {
        execute("INSERT INTO __android_sync_cursor_v2(instance,device,seq) VALUES(?,?,?)",
            listOf(text(instance), text(device), number(sequence)))
    }
    for (watermark in state.watermarks) {
        execute("""INSERT INTO __dstu_identity_watermark(instance_id,source_device_id,kind,last_applied_offset) VALUES(?,?,?,?)
            ON CONFLICT(instance_id,source_device_id,kind) DO UPDATE SET last_applied_offset=max(last_applied_offset,excluded.last_applied_offset)""".trimIndent(),
            listOf(text(instance), text(watermark.sourceDeviceId), text(watermark.kind), number(watermark.lastAppliedOffset)))
    }
    backupIdentityPublications(matched)
    for (processedKey in state.processedKeys + key) {
        execute("INSERT INTO __dstu_identity_legacy_key(instance_id,object_key) VALUES(?,?) ON CONFLICT(instance_id,object_key) DO NOTHING",
            listOf(text(instance), text(processedKey)))
    }
    execute("INSERT INTO __dstu_identity_receipt(takeover_key,instance_id,device_id,sidecar_sha256) VALUES(?,?,?,?)",
        listOf(text(key), text(instance), text(writer), text(matched.sidecarSha256)))
}

private fun SqlOperations.backupIdentityCheckBinding(instance: String, provider: String, endpoint: String) {
    val bindings = query("SELECT instance_id,provider,endpoint_hint FROM __dstu_identity_binding") {
        Triple(it.text(0), it.text(1), it.text(2))
    }
    if (bindings.any { it != Triple(instance, provider, endpoint) }) throw BackupIdentityException(BackupIdentityFailure.BINDING_CONFLICT)
}

private fun SqlOperations.backupIdentityCheckPending(instance: String, writer: String) {
    if (!backupIdentityHasPendingTable()) return
    val pending = query("SELECT 1 FROM sync_pending_publication WHERE instance=? AND device=? LIMIT 1", listOf(text(instance), text(writer))) { it.long(0) }
    if (pending.isNotEmpty()) throw BackupIdentityException(BackupIdentityFailure.PENDING_PUBLICATION)
}

private fun SqlOperations.backupIdentityCheckSequences(matched: ContinuityValidation.Matched) {
    val instance = matched.state.repository.instanceId
    val writer = matched.state.upload.deviceId
    if (matched.state.upload.lastSeq == Long.MAX_VALUE) throw BackupIdentityException(BackupIdentityFailure.INVALID_SEQUENCE)
    val upload = query("""SELECT 1 FROM sync_upload_state WHERE instance=? AND device=?
        AND (typeof(seq)!='integer' OR seq<0 OR seq>?) LIMIT 1""".trimIndent(), listOf(text(instance), text(writer), number(matched.state.upload.lastSeq))) { it.long(0) }
    val watermark = query("""SELECT 1 FROM __dstu_identity_watermark WHERE instance_id=?
        AND (typeof(last_applied_offset)!='integer' OR last_applied_offset<0) LIMIT 1""".trimIndent(), listOf(text(instance))) { it.long(0) }
    val published = query("""SELECT 1 FROM __dstu_identity_publication WHERE instance_id=? AND device_id=?
        AND (typeof(seq)!='integer' OR seq<=0) LIMIT 1""".trimIndent(), listOf(text(instance), text(writer))) { it.long(0) }
    if (upload.isNotEmpty() || watermark.isNotEmpty() || published.isNotEmpty()) throw BackupIdentityException(BackupIdentityFailure.INVALID_SEQUENCE)
}

private fun SqlOperations.backupIdentityPublications(matched: ContinuityValidation.Matched) {
    val instance = matched.state.repository.instanceId
    for (publication in matched.state.publications) {
        val scope = listOf(text(instance), text(publication.deviceId), text(publication.kind))
        val byOperation = query("SELECT seq FROM __dstu_identity_publication WHERE instance_id=? AND device_id=? AND kind=? AND operation_id=?",
            scope + text(publication.operationId)) { it.long(0) }
        val bySequence = query("SELECT operation_id FROM __dstu_identity_publication WHERE instance_id=? AND device_id=? AND kind=? AND seq=?",
            scope + number(publication.seq)) { it.text(0) }
        if (byOperation.any { it != publication.seq } || bySequence.any { it != publication.operationId }) {
            throw BackupIdentityException(BackupIdentityFailure.PUBLICATION_CONFLICT)
        }
        execute("INSERT INTO __dstu_identity_publication(instance_id,device_id,kind,operation_id,seq) VALUES(?,?,?,?,?) ON CONFLICT(instance_id,device_id,kind,operation_id) DO NOTHING",
            scope + listOf(text(publication.operationId), number(publication.seq)))
    }
}
