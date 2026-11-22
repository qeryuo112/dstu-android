package com.deepstudent.app.graph

/**
 * Graph for the isolatedProcess service probe. It deliberately has no main
 * resource factory and no general Binder surface.
 */
class IsolatedGraph internal constructor(
    identity: ProcessIdentity,
    dispatchers: GraphDispatchers,
    graphId: String,
    logger: GraphLogger,
) : BaseProcessGraph(identity, dispatchers, graphId, logger) {
    init {
        require(identity.role == ProcessRole.ISOLATED) {
            "IsolatedGraph cannot be created for ${identity.role}"
        }
    }

    companion object {
        internal fun create(
            identity: ProcessIdentity,
            dispatchers: GraphDispatchers,
            graphId: String,
            logger: GraphLogger,
        ): IsolatedGraph = IsolatedGraph(identity, dispatchers, graphId, logger)
    }
}
