package com.deepstudent.app.graph

/**
 * Process-local composition root. Exactly one instance is owned by
 * DstuApplication in each Android process.
 */
sealed interface AppGraph : AutoCloseable {
    val identity: ProcessIdentity
    val dispatchers: GraphDispatchers
    val graphId: String
    val businessResourcesInitialized: Boolean

    fun report(): GraphReport
}

data class GraphReport(
    val role: ProcessRole,
    val processName: String?,
    val processNameSource: ProcessNameSource,
    val graphId: String,
    val businessResourcesInitialized: Boolean,
)
