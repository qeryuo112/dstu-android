package com.deepstudent.services.engine.api

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.id.EngineId
import com.deepstudent.core.common.id.TaskId

data class EngineDescriptor(
    val engineId: EngineId,
    val version: String,
    val capabilities: Set<String> = emptySet(),
) {
    init {
        require(version.isNotBlank()) { "engine version must not be blank" }
    }
}

sealed interface EngineEvent {
    data class StateChanged(val state: EngineState) : EngineEvent

    data class Progress(val taskId: TaskId?, val percent: Int) : EngineEvent {
        init {
            require(percent in 0..100) { "engine progress must be within 0..100" }
        }
    }

    data class Failed(val error: DstuError) : EngineEvent
}

enum class EngineState {
    IDLE,
    STARTING,
    READY,
    BUSY,
    STOPPING,
    CLOSED,
}

interface EngineHandle {
    val engineId: EngineId

    val state: EngineState

    val alive: Boolean

    suspend fun stop()

    fun close()
}

/** A narrow factory. It owns startup only; execution policy belongs to callers. */
fun interface Engine {
    suspend fun start(): EngineHandle
}
