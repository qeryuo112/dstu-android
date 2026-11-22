package com.deepstudent.app.graph

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Narrow, injectable dispatcher set owned by a process graph.
 *
 * Consumers receive this small set instead of the whole [AppGraph]. Tests can
 * substitute deterministic dispatchers without constructing Android runtime
 * objects or business resources.
 */
interface GraphDispatchers {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
}

object DefaultGraphDispatchers : GraphDispatchers {
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
}
