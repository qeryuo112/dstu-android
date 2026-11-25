package com.deepstudent.app.diagnostics

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.platform.database.DatabaseLogEvent
import com.deepstudent.platform.database.DatabaseStatus
import com.deepstudent.platform.database.MigrationLogEvent
import com.deepstudent.platform.security.SecurityAuditEvent
import com.deepstudent.services.backup.data.RestoreEvent

/** Only typed, already-emitted producer facts are bridged; never exception text or raw logs. */
internal object S231DomainEvents {
    fun restore(event: RestoreEvent): LogRecord = LogRecord(
        level = if (event.error == null) LogLevel.INFO else LogLevel.ERROR,
        event = "backup.restore",
        correlationId = CorrelationId.parse(event.operationId),
        fields = JsonValue.Object.Builder().put("owner", event.owner).put("phase", event.phase.name)
            .put("status", event.status.name).put("elapsedMs", event.elapsedMillis).build(),
        error = event.error?.let { safeError(it.name) },
    )

    fun database(event: DatabaseLogEvent): LogRecord = LogRecord(
        level = if (event.status == DatabaseStatus.SUCCESS) LogLevel.INFO else LogLevel.ERROR,
        event = "database.operation",
        fields = JsonValue.Object.Builder().put("owner", event.owner).put("phase", event.phase.name)
            .put("status", event.status.name).put("elapsedMs", event.elapsedMillis).build(),
        error = when (event.status) {
            DatabaseStatus.SUCCESS -> null
            DatabaseStatus.FAILED -> safeError("DATABASE_OPERATION_FAILED")
            DatabaseStatus.TIMED_OUT -> safeError("DATABASE_TIMED_OUT")
        },
    )

    fun migration(event: MigrationLogEvent): LogRecord = LogRecord(
        level = if (event.diagnostic == null) LogLevel.INFO else LogLevel.ERROR,
        event = "database.migration",
        correlationId = CorrelationId.parse(event.correlationId),
        fields = JsonValue.Object.Builder().put("owner", event.owner).put("database", event.database.id)
            .put("phase", "MIGRATION").put("status", event.status).put("elapsedMs", event.durationMillis)
            .apply { event.details.status?.stage?.name?.let { put("stage", it) } }.build(),
        error = event.diagnostic?.let { safeError(it.name) },
    )

    fun security(event: SecurityAuditEvent): LogRecord = LogRecord(
        level = if (event.errorCode == null) LogLevel.INFO else LogLevel.ERROR,
        event = "security.crypto",
        correlationId = event.correlationId?.let(CorrelationId::parse),
        fields = JsonValue.Object.Builder().put("owner", event.owner).put("status", event.outcome.name)
            .apply {
                event.phase?.name?.let { put("phase", it) }
                event.elapsedMs?.let { put("elapsedMs", it) }
            }.build(),
        error = event.errorCode?.let { safeError(it) },
    )

    private fun safeError(code: String): DstuError = DstuError.internal(code, "operation failed")
}
