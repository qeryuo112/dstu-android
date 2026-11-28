package com.deepstudent.services.providers.data

import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Fixed diagnostic helpers for the S3.08 boundary. Structured logs record correlation, stage,
 * fixed code and elapsed time but never secrets, endpoint/header values or raw text.
 */
object RelayDiagnostics {
    /** Canonical HTTP reason phrases (frozen `http-status.ts` table subset for display). */
    private val REASONS: Map<Int, String> = mapOf(
        200 to "OK", 201 to "Created", 202 to "Accepted", 204 to "No Content",
        206 to "Partial Content", 301 to "Moved Permanently", 302 to "Found", 303 to "See Other",
        304 to "Not Modified", 307 to "Temporary Redirect", 308 to "Permanent Redirect",
        400 to "Bad Request", 401 to "Unauthorized", 402 to "Payment Required", 403 to "Forbidden",
        404 to "Not Found", 405 to "Method Not Allowed", 406 to "Not Acceptable",
        407 to "Proxy Authentication Required", 408 to "Request Timeout", 409 to "Conflict",
        410 to "Gone", 411 to "Length Required", 412 to "Precondition Failed",
        413 to "Payload Too Large", 414 to "URI Too Long", 415 to "Unsupported Media Type",
        416 to "Range Not Satisfiable", 418 to "I'm a teapot", 421 to "Misdirected Request",
        422 to "Unprocessable Entity", 423 to "Locked", 424 to "Failed Dependency",
        425 to "Too Early", 426 to "Upgrade Required", 428 to "Precondition Required",
        429 to "Too Many Requests", 431 to "Request Header Fields Too Large",
        451 to "Unavailable For Legal Reasons", 500 to "Internal Server Error",
        501 to "Not Implemented", 502 to "Bad Gateway", 503 to "Service Unavailable",
        504 to "Gateway Timeout", 505 to "HTTP Version Not Supported",
    )

    /** Rust `StatusCode` display: `401 Unauthorized`; bare number when unknown. */
    fun httpStatusDisplay(status: Int): String {
        val reason = REASONS[status] ?: return status.toString()
        return "$status $reason"
    }
}

/** Structured, redacted lifecycle log for one S3.08 operation (correlation/stage/code/elapsed). */
internal class S308Trace(private val logger: Logger = NoopLogger, private val category: String, private val eventPrefix: String) {
    private val correlationId: CorrelationId = CorrelationId.parse(UUID.randomUUID().toString())
    private val startedAt: Long = System.nanoTime()

    fun record(phase: String, code: String? = null, fields: JsonValue.Object = JsonValue.Object.EMPTY) {
        val builder = JsonValue.Object.Builder()
            .put("owner", "services/providers")
            .put("category", category)
            .put("stage", phase)
            .put("elapsedMs", elapsedMillis())
        code?.let { builder.put("code", it) }
        builder.putAll(fields)
        val level = if (phase == STAGE_FAILED) LogLevel.WARN else LogLevel.INFO
        runCatching { logger.log(LogRecord(level, "$eventPrefix.$phase", correlationId = correlationId, fields = builder.build())) }
    }

    fun elapsedMillis(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt).coerceAtLeast(0)

    companion object {
        const val STAGE_STARTED = "started"
        const val STAGE_COMPLETED = "completed"
        const val STAGE_FAILED = "failed"
        const val STAGE_CANCELLED = "cancelled"
    }
}
