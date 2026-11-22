package com.deepstudent.app.backup

import android.os.SystemClock
import android.util.Log
import com.deepstudent.core.common.id.Digests
import com.deepstudent.platform.database.MigrationException
import com.deepstudent.platform.database.MigrationReport
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlReadOperations
import com.deepstudent.services.backup.api.RestorePlan
import com.deepstudent.services.resources.data.assets.VfsRestoreReferenceException

/** Additional legacy constraints, evaluated on the staged databases only and before publication. */
internal class LegacyRestoreChecks(plan: RestorePlan, private val correlationId: String) {
    private val versions = checkNotNull(plan.legacyImport).schemaVersions

    fun checkDatabase(database: SharedDatabase, action: () -> Unit) = checked("database_${database.id}", action)
    fun checkResources(action: () -> Unit) = checked("references", action)

    fun checkSourceVersion(id: SharedDatabase, migration: MigrationReport?) {
        if (migration?.fromVersion?.toLong() != versions.getValue(id.id)) fail(LegacyCheckError.SOURCE_SCHEMA_MISMATCH)
    }

    fun checkReferences(database: SqlReadOperations) {
        verifyFolderNotes(database)
        verifyNoteResources(database)
    }

    private fun checked(phase: String, action: () -> Unit) {
        val start = SystemClock.elapsedRealtime()
        log(phase, "started", start)
        try {
            action()
            log(phase, "verified", start)
        } catch (failure: Exception) {
            val code = when (failure) {
                is LegacyCheckFailure -> failure.error.name
                is RestoreBlobException -> failure.code.name
                is VfsRestoreReferenceException -> failure.code.name
                is MigrationException -> failure.diagnostic.name
                else -> "STAGED_VALIDATION"
            }
            log(phase, "failed", start, code)
            throw failure
        }
    }

    private fun log(phase: String, state: String, start: Long, code: String = "none") {
        Log.i("DstuBackupRestore", "event=backup.legacy_import owner=app.backup correlationId=$correlationId " +
            "phase=$phase state=$state elapsedMs=${SystemClock.elapsedRealtime() - start} code=$code")
    }
}

private fun verifyFolderNotes(database: SqlReadOperations) {
    database.query<Unit>("""
        SELECT 1 FROM folder_items i LEFT JOIN notes n ON n.id = i.item_id
        WHERE i.item_type = 'note' AND i.deleted_at IS NULL AND n.id IS NULL
        LIMIT 1
    """.trimIndent()) { fail(LegacyCheckError.MISSING_FOLDER_NOTE) }
}

private const val MAX_INLINE_NOTE_BYTES = 16 * 1024 * 1024

private fun verifyNoteResources(database: SqlReadOperations) {
    // Do not materialize an attacker-sized TEXT cell or keep note content in the query result list.
    database.query<Unit>("""
        SELECT n.id, r.hash, CASE WHEN length(CAST(r.data AS BLOB)) <= $MAX_INLINE_NOTE_BYTES THEN r.data END
        FROM notes n JOIN resources r ON r.id = n.resource_id
        WHERE r.type = 'note' AND r.storage_mode = 'inline'
    """.trimIndent()) { row ->
        if (row.isNull(2)) fail(LegacyCheckError.INVALID_INLINE_NOTE)
        val hash = Digests.sha256HexUtf8("${row.text(0)}:${row.text(2)}")
        if (!hash.equals(row.text(1), ignoreCase = true)) fail(LegacyCheckError.NOTE_HASH_MISMATCH)
    }
}

private enum class LegacyCheckError { SOURCE_SCHEMA_MISMATCH, MISSING_FOLDER_NOTE, INVALID_INLINE_NOTE, NOTE_HASH_MISMATCH }

private class LegacyCheckFailure(val error: LegacyCheckError) : IllegalStateException(error.name)
private fun fail(error: LegacyCheckError): Nothing = throw LegacyCheckFailure(error)
