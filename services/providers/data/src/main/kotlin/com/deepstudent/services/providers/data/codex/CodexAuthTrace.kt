package com.deepstudent.services.providers.data.codex

import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Fixed diagnostic fields only: stage, outcome, generation and elapsed time.
 * Tokens, state values, authorization URLs, user codes and account ids never enter a record.
 */
internal class CodexAuthTrace(private val logger: Logger) {
    private val correlationId = CorrelationId.parse(UUID.randomUUID().toString())
    private val started = System.nanoTime()

    fun record(phase: String, outcome: String? = null, generation: Long? = null) {
        val fields = JsonValue.Object.Builder()
            .put("owner", "services/providers")
            .put("category", "openai_codex_auth")
            .put("phase", phase)
            .put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).coerceAtLeast(0))
        outcome?.let { fields.put("outcome", it) }
        generation?.let { fields.put("generation", it) }
        val level = if (outcome == null || outcome == "COMPLETED" || outcome == "AUTHENTICATED") LogLevel.INFO else LogLevel.WARN
        runCatching { logger.log(LogRecord(level, "providers.codex.auth.$phase", correlationId = correlationId, fields = fields.build())) }
    }
}
