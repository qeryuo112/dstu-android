package com.deepstudent.core.common.logging

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

data class LogRecord(
    val level: LogLevel,
    val event: String,
    val taskId: TaskId? = null,
    val correlationId: CorrelationId? = null,
    val fields: JsonValue.Object = JsonValue.Object.EMPTY,
    val error: DstuError? = null,
) {
    init {
        require(event.isNotBlank()) { "log event must not be blank" }
    }
}

interface Logger {
    fun log(record: LogRecord)

    fun debug(event: String, fields: JsonValue.Object = JsonValue.Object.EMPTY) =
        log(LogRecord(LogLevel.DEBUG, event, fields = fields))

    fun info(event: String, fields: JsonValue.Object = JsonValue.Object.EMPTY) =
        log(LogRecord(LogLevel.INFO, event, fields = fields))

    fun warn(event: String, error: DstuError? = null, fields: JsonValue.Object = JsonValue.Object.EMPTY) =
        log(LogRecord(LogLevel.WARN, event, fields = fields, error = error))

    fun error(event: String, error: DstuError? = null, fields: JsonValue.Object = JsonValue.Object.EMPTY) =
        log(LogRecord(LogLevel.ERROR, event, fields = fields, error = error))
}

object NoopLogger : Logger {
    override fun log(record: LogRecord) = Unit
}

fun JsonValue.Object.withField(key: String, value: JsonValue): JsonValue.Object {
    val builder = JsonValue.Object.Builder()
    builder.putAll(this)
    builder.put(key, value)
    return builder.build()
}
