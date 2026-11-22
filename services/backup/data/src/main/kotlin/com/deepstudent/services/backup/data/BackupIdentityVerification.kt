package com.deepstudent.services.backup.data

import com.deepstudent.platform.database.SqlReadOperations
import com.deepstudent.services.backup.backend.ContinuityCursor
import com.deepstudent.services.backup.backend.ContinuityValidation

/** A copied archive cannot silently advance an authorized consumption or publication baseline. */
internal fun SqlReadOperations.backupIdentityRequireAppliedState(
    matched: ContinuityValidation.Matched,
    aliases: BackupIdentityPeerAliases,
) {
    val state = matched.state
    val saved = backupIdentitySnapshot(state.repository.instanceId, state.upload.deviceId)
    val runtime = backupIdentityCursorAliases(matched, aliases).map { (device, sequence) -> ContinuityCursor(device, sequence) }.toSet()
    if (saved.binding != state.repository || saved.publishedSequence != state.upload.lastSeq ||
        saved.sourceCursors.toSet() != state.cursors.toSet() || saved.cursors.toSet() != runtime ||
        saved.watermarks.toSet() != state.watermarks.toSet() || saved.publications.toSet() != state.publications.toSet() ||
        saved.processedKeys.toSet() != (state.processedKeys + matched.takeoverKey).toSet()) {
        throw BackupIdentityException(BackupIdentityFailure.APPLIED_STATE_MISMATCH)
    }
}
