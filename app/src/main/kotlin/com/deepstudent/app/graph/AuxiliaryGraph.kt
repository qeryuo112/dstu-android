package com.deepstudent.app.graph

/**
 * Minimal graph for processes not registered as main, engine or isolated.
 * Treating an unknown process as main would risk opening the unique business
 * database in the wrong place.
 */
class AuxiliaryGraph internal constructor(
    identity: ProcessIdentity,
    dispatchers: GraphDispatchers,
    graphId: String,
    logger: GraphLogger,
) : BaseProcessGraph(identity, dispatchers, graphId, logger) {
    companion object {
        internal fun create(
            identity: ProcessIdentity,
            dispatchers: GraphDispatchers,
            graphId: String,
            logger: GraphLogger,
        ): AuxiliaryGraph = AuxiliaryGraph(identity, dispatchers, graphId, logger)
    }
}
