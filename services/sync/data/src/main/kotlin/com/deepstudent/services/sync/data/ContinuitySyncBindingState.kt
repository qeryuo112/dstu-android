package com.deepstudent.services.sync.data

import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.SyncFailure
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Only the exact, fsynced published file is read; atomic-write temp files are never references. */
internal sealed interface ContinuityWriterState {
    data class Active(val binding: ContinuitySyncBinding) : ContinuityWriterState
    data class Adopting(val binding: ContinuitySyncBinding) : ContinuityWriterState
    data class Rotating(val operationId: String) : ContinuityWriterState
}

internal object ContinuitySyncBindingState {
    fun encode(state: ContinuityWriterState): String = buildJsonObject {
        put("version", 1)
        when (state) {
            is ContinuityWriterState.Active -> { put("phase", "active"); binding(state.binding) }
            is ContinuityWriterState.Adopting -> { put("phase", "adopting"); binding(state.binding) }
            is ContinuityWriterState.Rotating -> { put("phase", "rotating"); put("operationId", state.operationId) }
        }
    }.toString()

    private fun kotlinx.serialization.json.JsonObjectBuilder.binding(value: ContinuitySyncBinding) {
        put("operationId", value.operationId)
        put("writerId", value.writerId)
        put("instanceId", value.instanceId)
        put("repositoryUrl", value.repositoryUrl)
    }

    fun decode(raw: String): ContinuityWriterState {
        try {
            val obj = Json.parseToJsonElement(raw).jsonObject
            fun text(name: String) = obj.getValue(name).jsonPrimitive.let { require(it.isString); it.content }
            val state = when (text("phase")) {
                "active", "adopting" -> {
                    val value = ContinuitySyncBinding(text("operationId"), text("writerId"), text("instanceId"), text("repositoryUrl"))
                    value.validate()
                    if (text("phase") == "active") ContinuityWriterState.Active(value) else ContinuityWriterState.Adopting(value)
                }
                "rotating" -> ContinuityWriterState.Rotating(text("operationId").also(::requireRotationUuid))
                else -> error("unsupported state")
            }
            // Our encoder is deterministic; this also rejects duplicate and unknown persisted fields.
            require(raw == encode(state))
            return state
        } catch (_: Exception) {
            throw SyncFailure(SyncError.STORAGE)
        }
    }
}

internal fun requireRotationUuid(operationId: String) {
    val uuid = try { UUID.fromString(operationId) } catch (_: IllegalArgumentException) { null }
    if (uuid == null || uuid.toString() != operationId || uuid.variant() != 2 || uuid.version() != 4) {
        throw SyncFailure(SyncError.INVALID_CONFIGURATION)
    }
}
