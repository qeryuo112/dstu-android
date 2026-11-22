package com.deepstudent.app.sync

import com.deepstudent.app.graph.ProcessRole
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class LazySyncOwner<T : AutoCloseable>(
    private val role: () -> ProcessRole,
    private val open: suspend () -> T,
) {
    private val mutex = Mutex()
    @Volatile private var resource: T? = null
    private var closed = false
    val isInitialized: Boolean get() = resource != null

    suspend fun get(): T = mutex.withLock {
        check(role() == ProcessRole.MAIN) { "Sync requires the main process" }
        check(!closed) { "Sync owner is closed" }
        resource ?: open().also { resource = it }
    }

    suspend fun close() = mutex.withLock {
        if (!closed) {
            closed = true
            resource?.close()
            resource = null
        }
    }
}
