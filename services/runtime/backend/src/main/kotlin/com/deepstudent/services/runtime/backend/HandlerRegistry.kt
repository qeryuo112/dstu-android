package com.deepstudent.services.runtime.backend

import com.deepstudent.services.runtime.api.RuntimeTaskHandler
import com.deepstudent.services.runtime.api.BusinessCompletionSource
import com.deepstudent.services.runtime.api.TaskKind

/** The composition root registers business handlers; the runtime does not import features. */
class HandlerRegistry(
    handlers: Map<TaskKind, RuntimeTaskHandler>,
    completionSources: Map<TaskKind, BusinessCompletionSource> = emptyMap(),
) {
    private val handlers = handlers.toMap()
    private val sources = completionSources.toMap()

    init {
        require(sources.keys.all { it in handlers }) { "completion source has no handler" }
    }

    fun find(kind: TaskKind): RuntimeTaskHandler? = handlers[kind]
    fun completionSource(kind: TaskKind): BusinessCompletionSource? = sources[kind]
}
