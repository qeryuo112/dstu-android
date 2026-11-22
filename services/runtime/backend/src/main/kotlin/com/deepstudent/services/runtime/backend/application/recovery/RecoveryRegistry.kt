package com.deepstudent.services.runtime.backend.application.recovery

import com.deepstudent.services.runtime.api.BusinessCompletionSource
import com.deepstudent.services.runtime.api.RecoveryRegistration
import com.deepstudent.services.runtime.api.TaskKind
import java.util.IdentityHashMap

internal class RecoveryRegistry(registrations: List<RecoveryRegistration>) {
    private val entries = registrations.associateBy { it.kind }
    val sources: List<BusinessCompletionSource>

    init {
        require(entries.size == registrations.size) { "duplicate recovery registration" }
        val seen = IdentityHashMap<BusinessCompletionSource, Boolean>()
        sources = registrations.map { it.completionSource }.filter { seen.put(it, true) == null }
    }

    operator fun get(kind: TaskKind): RecoveryRegistration? = entries[kind]
}
