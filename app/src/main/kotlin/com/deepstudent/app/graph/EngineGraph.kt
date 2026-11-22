package com.deepstudent.app.graph

/**
 * Graph for the real ":engine" process. It has no main-resource factory by
 * construction, so business databases and main runtime services cannot be
 * initialized here.
 */
class EngineGraph internal constructor(
    identity: ProcessIdentity,
    dispatchers: GraphDispatchers,
    graphId: String,
    logger: GraphLogger,
) : BaseProcessGraph(identity, dispatchers, graphId, logger) {
    init {
        require(identity.role == ProcessRole.ENGINE) {
            "EngineGraph cannot be created for ${identity.role}"
        }
    }

    companion object {
        internal fun create(
            identity: ProcessIdentity,
            dispatchers: GraphDispatchers,
            graphId: String,
            logger: GraphLogger,
        ): EngineGraph = EngineGraph(identity, dispatchers, graphId, logger)
    }
}
