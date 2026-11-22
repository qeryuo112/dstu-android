package com.deepstudent.app.environments

import com.deepstudent.features.environments.api.EnvironmentManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class OpenedEnvironment(
    val manager: EnvironmentManager,
    val close: () -> Unit,
)

/** Construction and workspace restoration do no environment I/O. Owner survives panel/Activity recreation. */
internal class LazyEnvironmentOwner(private val open: suspend () -> OpenedEnvironment) {
    private val mutex = Mutex()
    @Volatile private var resources: OpenedEnvironment? = null
    private var closed = false
    val isInitialized: Boolean get() = resources != null

    suspend fun get(): EnvironmentManager = mutex.withLock {
        check(!closed) { "environment owner is closed" }
        (resources ?: open().also { resources = it }).manager
    }

    suspend fun close() = mutex.withLock {
        if (closed) return@withLock
        closed = true
        resources?.close?.invoke()
        resources = null
    }
}
