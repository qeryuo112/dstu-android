package com.deepstudent.app.execution

import com.deepstudent.app.graph.ProcessRole
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.services.execution.api.ExecutionSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns one main-process system, independently of any workspace panel or Activity. */
internal class LazyExecutionOwner(
    private val processRole: () -> ProcessRole,
    private val logger: Logger = NoopLogger,
    private val open: suspend () -> ExecutionSystem,
) {
    private val mutex = Mutex()
    @Volatile private var system: ExecutionSystem? = null
    private var closed = false
    val isInitialized: Boolean get() = system != null

    suspend fun get(): ExecutionSystem = mutex.withLock {
        check(processRole() == ProcessRole.MAIN) { "Execution owner requires the main process" }
        check(!closed) { "Execution owner is closed" }
        system ?: openSystem().also {
            system = it
            logger.info("execution.owner.opened")
        }
    }

    private suspend fun openSystem(): ExecutionSystem = try {
        open()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        // Only a fixed event is recorded, never the exception message or command data.
        logger.warn("execution.owner.open_failed")
        throw failure
    }

    suspend fun close() = mutex.withLock {
        if (closed) return@withLock
        closed = true
        val opened = system
        system = null
        if (opened != null) {
            try {
                opened.close()
                logger.info("execution.owner.closed")
            } catch (failure: Exception) {
                logger.warn("execution.owner.close_failed")
                throw failure
            }
        }
    }
}
