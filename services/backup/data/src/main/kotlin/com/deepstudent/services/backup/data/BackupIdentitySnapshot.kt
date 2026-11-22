package com.deepstudent.services.backup.data

import com.deepstudent.platform.database.SqlReadOperations
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.backup.backend.ContinuityCursor
import com.deepstudent.services.backup.backend.ContinuityPublication
import com.deepstudent.services.backup.backend.ContinuityRepository
import com.deepstudent.services.backup.backend.ContinuityWatermark
import java.util.Collections

internal class BackupIdentityCursorSnapshot(val runtime: List<ContinuityCursor>, val source: List<ContinuityCursor>)

/** Version-1 local identity state, portable to another adapter without changing desktop sync wire. */
class BackupIdentitySnapshot internal constructor(
    val binding: ContinuityRepository?,
    val publishedSequence: Long?,
    cursors: BackupIdentityCursorSnapshot,
    watermarks: List<ContinuityWatermark>,
    publications: List<ContinuityPublication>,
    processedKeys: List<String>,
    receipts: List<BackupIdentityReceipt>,
) {
    val cursors = Collections.unmodifiableList(ArrayList(cursors.runtime))
    val sourceCursors = Collections.unmodifiableList(ArrayList(cursors.source))
    val watermarks = Collections.unmodifiableList(ArrayList(watermarks))
    val publications = Collections.unmodifiableList(ArrayList(publications))
    val processedKeys = Collections.unmodifiableList(ArrayList(processedKeys))
    val receipts = Collections.unmodifiableList(ArrayList(receipts))
    override fun toString(): String = "BackupIdentitySnapshot(redacted)"
}

class BackupIdentityReceipt internal constructor(
    val takeoverKey: String, val writerId: String, val sidecarSha256: String,
) {
    override fun toString(): String = "BackupIdentityReceipt(redacted)"
}

internal fun SqlReadOperations.backupIdentitySnapshot(instance: String, writer: String): BackupIdentitySnapshot {
    val scope = listOf(SqlValue.Text(instance))
    val writerScope = scope + SqlValue.Text(writer)
    val binding = query("SELECT provider,endpoint_hint FROM __dstu_identity_binding WHERE instance_id=?", scope) {
        ContinuityRepository(instance, it.text(0), it.text(1))
    }.singleOrNull()
    val sequence = query("SELECT seq FROM sync_upload_state WHERE instance=? AND device=?", writerScope) { it.long(0) }.singleOrNull()
    val cursors = query("SELECT device,seq FROM __android_sync_cursor_v2 WHERE instance=? ORDER BY device", scope) {
        ContinuityCursor(it.text(0), it.long(1))
    }
    val sourceCursors = query("SELECT uploader_device_id,last_seq FROM __dstu_identity_source_cursor WHERE instance_id=? ORDER BY uploader_device_id", scope) {
        ContinuityCursor(it.text(0), it.long(1))
    }
    val watermarks = query("SELECT source_device_id,kind,last_applied_offset FROM __dstu_identity_watermark WHERE instance_id=? ORDER BY source_device_id,kind", scope) {
        ContinuityWatermark(it.text(0), it.text(1), it.long(2))
    }
    val publications = query("SELECT kind,operation_id,seq FROM __dstu_identity_publication WHERE instance_id=? AND device_id=? ORDER BY kind,operation_id", writerScope) {
        ContinuityPublication(writer, it.text(0), it.text(1), it.long(2))
    }
    val keys = query("SELECT object_key FROM __dstu_identity_legacy_key WHERE instance_id=? ORDER BY object_key", scope) { it.text(0) }
    val receipts = query("SELECT takeover_key,device_id,sidecar_sha256 FROM __dstu_identity_receipt WHERE instance_id=? ORDER BY takeover_key", scope) {
        BackupIdentityReceipt(it.text(0), it.text(1), it.text(2))
    }
    return BackupIdentitySnapshot(binding, sequence, BackupIdentityCursorSnapshot(cursors, sourceCursors), watermarks, publications, keys, receipts)
}
