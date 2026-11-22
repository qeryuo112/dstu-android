package com.deepstudent.services.sync.data.capture

import com.deepstudent.platform.database.DatabaseDiagnostic

/** Attribute a trigger only when its verified body itself uses the absent pseudo-row. */
internal object CaptureTriggerFaultLocator {
    private val sqlCommentsAndStrings = Regex("(?s)'(?:''|[^'])*'|--[^\\r\\n]*|/\\*.*?\\*/")
    private val newRow = Regex("(?i)\\bNEW\\s*\\.")
    private val oldRow = Regex("(?i)\\bOLD\\s*\\.")

    fun locate(entry: SyncCaptureEntry, op: CaptureOperation, actual: Map<String, String>, diagnostic: String?): String? {
        if (diagnostic != DatabaseDiagnostic.SQL_NO_SUCH_COLUMN.fixedMessage) return null
        val absent = when (op) {
            CaptureOperation.INSERT -> oldRow
            CaptureOperation.DELETE -> newRow
            CaptureOperation.UPDATE -> return null
        }
        return entry.triggers.filter { trigger ->
            val sql = actual[trigger.name].orEmpty().replace(sqlCommentsAndStrings, " ")
            trigger.operation == op && absent.containsMatchIn(sql)
        }.singleOrNull()?.name
    }
}
