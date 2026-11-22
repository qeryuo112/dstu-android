package com.deepstudent.app.runtime.script

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.runtime.api.RuntimeTaskContext
import com.deepstudent.services.runtime.api.RuntimeTaskHandler
import com.deepstudent.services.script.backend.PtcScriptHandler
import com.deepstudent.services.script.backend.PtcTaskContext

internal class ScriptRuntimeHandler(private val scripts: PtcScriptHandler) : RuntimeTaskHandler {
    override suspend fun execute(context: RuntimeTaskContext, payload: JsonValue) {
        scripts.execute(object : PtcTaskContext {
            override val taskId = context.taskId
            override val generation = context.generation
            override suspend fun checkpoint(value: JsonValue?) { context.checkpoint(value) }
        }, payload)
    }
}
