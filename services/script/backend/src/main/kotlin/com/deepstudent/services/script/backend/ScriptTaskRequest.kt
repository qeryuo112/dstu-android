package com.deepstudent.services.script.backend

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.script.api.ScriptLimits

data class ScriptTaskRequest(
    val source: String,
    val limits: ScriptLimits,
) {
    companion object {
        fun parse(payload: JsonValue): ScriptTaskRequest {
            val objectValue = payload as? JsonValue.Object
                ?: throw ScriptRequestException("INVALID_PAYLOAD", "script task payload must be an object")
            val source = (objectValue["script"] as? JsonValue.Text)?.value
                ?: throw ScriptRequestException("MISSING_SCRIPT", "script task requires a script string")
            if (source.isBlank()) {
                throw ScriptRequestException("EMPTY_SCRIPT", "script must not be empty")
            }
            val bytes = source.toByteArray(Charsets.UTF_8).size
            if (bytes > ScriptLimits.DEFAULT_MAX_SOURCE_BYTES) {
                throw ScriptRequestException("SOURCE_LIMIT", "script exceeds the source budget")
            }
            return ScriptTaskRequest(source, limits(objectValue))
        }

        private fun limits(payload: JsonValue.Object): ScriptLimits {
            val maxCalls = ScriptLimits.clampToolCalls(integer(payload["maxCalls"]))
            val wall = ScriptLimits.clampWallTime(integer(payload["wallTimeMillis"]))
            return ScriptLimits(
                maxToolCalls = maxCalls,
                wallTime = wall,
                maxMemoryBytes = bounded(
                    integer(payload["maxMemoryBytes"]),
                    ScriptLimits.MIN_MEMORY_BYTES,
                    ScriptLimits.DEFAULT_MAX_MEMORY_BYTES,
                ),
                maxStackBytes = bounded(
                    integer(payload["maxStackBytes"]),
                    ScriptLimits.MIN_STACK_BYTES,
                    ScriptLimits.DEFAULT_MAX_STACK_BYTES,
                ),
            )
        }

        private fun integer(value: JsonValue?): Long? =
            (value as? JsonValue.Number)?.asLongOrNull()

        private fun bounded(value: Long?, minimum: Long, maximum: Long): Long =
            value?.takeIf { it > 0 }?.coerceIn(minimum, maximum) ?: maximum
    }
}

class ScriptRequestException(val code: String, override val message: String) : IllegalArgumentException(message)
