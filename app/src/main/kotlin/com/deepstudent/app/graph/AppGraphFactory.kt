package com.deepstudent.app.graph

import java.util.UUID

/**
 * Creates exactly the graph required by a detected process role.
 *
 * Only the MAIN branch can receive [MainResourceFactory]. Engine, isolated and
 * auxiliary branches cannot open main-process business resources through this
 * API.
 */
internal class AppGraphFactory(
    private val dispatchers: GraphDispatchers = DefaultGraphDispatchers,
    private val logger: GraphLogger = AndroidGraphLogger,
    private val mainResourceFactory: MainResourceFactory? = null,
    private val graphIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    fun create(identity: ProcessIdentity): AppGraph {
        val graphId = graphIdFactory()
        val graph = when (identity.role) {
            ProcessRole.MAIN -> MainGraph.create(
                identity = identity,
                dispatchers = dispatchers,
                graphId = graphId,
                logger = logger,
                mainResourceFactory = mainResourceFactory,
            )
            ProcessRole.ENGINE -> EngineGraph.create(
                identity = identity,
                dispatchers = dispatchers,
                graphId = graphId,
                logger = logger,
            )
            ProcessRole.ISOLATED -> IsolatedGraph.create(
                identity = identity,
                dispatchers = dispatchers,
                graphId = graphId,
                logger = logger,
            )
            ProcessRole.AUXILIARY -> AuxiliaryGraph.create(
                identity = identity,
                dispatchers = dispatchers,
                graphId = graphId,
                logger = logger,
            )
        }
        logger.info(
            "graph.created",
            mapOf(
                "graphId" to graph.graphId,
                "role" to graph.identity.role,
                "processName" to graph.identity.processName,
                "processNameSource" to graph.identity.source,
                "graphType" to graph.javaClass.simpleName,
                "businessResourcesInitialized" to graph.businessResourcesInitialized,
            ),
        )
        return graph
    }
}
