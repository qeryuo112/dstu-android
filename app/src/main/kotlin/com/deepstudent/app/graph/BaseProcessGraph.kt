package com.deepstudent.app.graph

abstract class BaseProcessGraph internal constructor(
    override val identity: ProcessIdentity,
    override val dispatchers: GraphDispatchers,
    override val graphId: String,
    protected val logger: GraphLogger,
) : AppGraph {
    @Volatile
    private var closed = false

    override val businessResourcesInitialized: Boolean
        get() = false

    override fun report(): GraphReport {
        checkOpen()
        return GraphReport(
            role = identity.role,
            processName = identity.processName,
            processNameSource = identity.source,
            graphId = graphId,
            businessResourcesInitialized = businessResourcesInitialized,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        logger.info(
            "graph.closed",
            mapOf(
                "graphId" to graphId,
                "role" to identity.role,
                "processName" to identity.processName,
            ),
        )
    }

    protected fun checkOpen() {
        check(!closed) { "Graph '$graphId' is closed" }
    }
}
