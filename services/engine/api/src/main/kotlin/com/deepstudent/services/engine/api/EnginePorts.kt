package com.deepstudent.services.engine.api

import kotlinx.coroutines.flow.Flow

fun interface EngineObserver {
    fun events(): Flow<EngineEvent>
}

/**
 * Closes the handle on every exit, including cancellation. Cancellation is not
 * converted into an ordinary failure.
 */
suspend inline fun <T> EngineHandle.withOpen(block: () -> T): T =
    try {
        block()
    } finally {
        close()
    }
