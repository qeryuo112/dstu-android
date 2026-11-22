package com.deepstudent.features.workspace.backend.application

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException
import com.deepstudent.features.workspace.api.PanelHost
import com.deepstudent.features.workspace.api.PanelKey

sealed interface HostRegistration {
    data object Registered : HostRegistration
    data class Conflict(val existingKey: PanelKey) : HostRegistration
}

/** Registration only. It does not own host execution or panel state. */
class PanelHostRegistry(initial: Iterable<PanelHost> = emptyList()) {
    private val lock = Any()
    private val hosts = LinkedHashMap<String, PanelHost>()

    init {
        initial.forEach { host ->
            check(register(host) == HostRegistration.Registered) {
                "duplicate initial workspace panel host"
            }
        }
    }

    fun register(host: PanelHost): HostRegistration = synchronized(lock) {
        val key = host.key.value
        if (hosts.containsKey(key)) {
            HostRegistration.Conflict(host.key)
        } else {
            hosts[key] = host
            HostRegistration.Registered
        }
    }

    fun unregister(key: PanelKey): Boolean = synchronized(lock) {
        hosts.remove(key.value) != null
    }

    fun find(key: PanelKey): PanelHost? = synchronized(lock) { hosts[key.value] }

    fun requireHost(key: PanelKey): PanelHost =
        find(key) ?: throw DstuException(
            DstuError.notFound("WORKSPACE_HOST_MISSING", "workspace panel host is not registered"),
        )

    fun snapshot(): List<PanelHost> = synchronized(lock) { hosts.values.toList() }
}
