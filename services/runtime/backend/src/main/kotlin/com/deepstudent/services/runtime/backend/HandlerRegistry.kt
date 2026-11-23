package com.deepstudent.services.runtime.backend

import com.deepstudent.services.runtime.api.BusinessCompletionSource
import com.deepstudent.services.runtime.api.RuntimeTaskHandler
import com.deepstudent.services.runtime.api.TaskKind

/**
 * The composition root registers business handlers; the runtime does not import features.
 *
 * [resumableKinds] is an explicit allow-list for scheduled runs. Only these kinds may be
 * started by [RuntimeController.runScheduled], and each must have a completion source so a
 * system-stop yield can reconcile committed business facts before releasing the lease.
 */
class HandlerRegistry(
    handlers: Map<TaskKind, RuntimeTaskHandler>,
    completionSources: Map<TaskKind, BusinessCompletionSource> = emptyMap(),
    val resumableKinds: Set<TaskKind> = emptySet(),
) {
    private val handlers = handlers.toMap()
    private val sources = completionSources.toMap()

    init {
        require(sources.keys.all { it in handlers }) { "completion source has no handler" }
        require(resumableKinds.all { it in handlers }) { "resumable kind has no handler" }
        require(resumableKinds.all { it in sources }) { "resumable kind has no completion source" }
    }

    fun find(kind: TaskKind): RuntimeTaskHandler? = handlers[kind]
    fun completionSource(kind: TaskKind): BusinessCompletionSource? = sources[kind]
}
