package com.deepstudent.app.graph

class MainGraph internal constructor(
    identity: ProcessIdentity,
    dispatchers: GraphDispatchers,
    graphId: String,
    logger: GraphLogger,
    private val mainResourceFactory: MainResourceFactory?,
) : BaseProcessGraph(identity, dispatchers, graphId, logger) {
    init {
        require(identity.role == ProcessRole.MAIN) {
            "MainGraph cannot be created for ${identity.role}"
        }
    }

    private val mainOwner = LazyProcessOwner(
        name = "main-process-resources",
        logger = logger,
        opener = {
            val factory = mainResourceFactory ?: error(
                "No main-process resource owner is registered in S1.02; " +
                    "business databases and runtime services are not opened",
            )
            factory.open()
        },
    )

    override val businessResourcesInitialized: Boolean
        get() = mainOwner.isInitialized

    /**
     * Internal S1.02 seam. The real business owner is supplied by a later unit;
     * this method must not be replaced with an empty fake database or service.
     */
    internal fun mainProcessResources(): MainProcessResources = mainOwner.get()

    override fun close() {
        mainOwner.close()
        super.close()
    }

    companion object {
        internal fun create(
            identity: ProcessIdentity,
            dispatchers: GraphDispatchers,
            graphId: String,
            logger: GraphLogger,
            mainResourceFactory: MainResourceFactory?,
        ): MainGraph = MainGraph(
            identity = identity,
            dispatchers = dispatchers,
            graphId = graphId,
            logger = logger,
            mainResourceFactory = mainResourceFactory,
        )
    }
}
