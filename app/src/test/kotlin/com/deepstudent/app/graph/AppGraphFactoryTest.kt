package com.deepstudent.app.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AppGraphFactoryTest {
    private val ioDispatcher = NamedDispatcher("test-io")
    private val defaultDispatcher = NamedDispatcher("test-default")
    private val dispatchers = object : GraphDispatchers {
        override val io = ioDispatcher
        override val default = defaultDispatcher
    }

    @Test
    fun `main graph keeps resources lazy and uses injected dispatchers`() {
        val factory = CountingMainResourceFactory()
        val graph = newFactory(factory).create(testIdentity(ProcessRole.MAIN)) as MainGraph

        assertEquals(ProcessRole.MAIN, graph.identity.role)
        assertSame(dispatchers, graph.dispatchers)
        assertSame(ioDispatcher, graph.dispatchers.io)
        assertSame(defaultDispatcher, graph.dispatchers.default)
        assertEquals("graph-test", graph.graphId)
        assertFalse(graph.businessResourcesInitialized)
        assertEquals(0, factory.openCount)
        assertFalse(graph.report().businessResourcesInitialized)

        val resource = graph.mainProcessResources() as TestMainResource

        assertEquals(1, factory.openCount)
        assertSame(factory.resources.single(), resource)
        assertTrue(graph.businessResourcesInitialized)
        assertTrue(graph.report().businessResourcesInitialized)

        graph.close()
        assertEquals(1, resource.closeCount)
        assertFalse(graph.businessResourcesInitialized)
        assertThrows(IllegalStateException::class.java) { graph.mainProcessResources() }
        assertClosedReportFails(graph)
    }

    @Test
    fun `main graph without registered owner fails instead of faking a database`() {
        val graph = newFactory(null).create(testIdentity(ProcessRole.MAIN)) as MainGraph

        assertFalse(graph.businessResourcesInitialized)
        val error = assertThrows(IllegalStateException::class.java) {
            graph.mainProcessResources()
        }

        assertTrue(error.message.orEmpty().contains("S1.02"))
        assertFalse(graph.businessResourcesInitialized)
    }

    @Test
    fun `engine graph cannot receive or open main resources`() {
        val resources = CountingMainResourceFactory()
        val graph = newFactory(resources).create(testIdentity(ProcessRole.ENGINE))

        assertTrue(graph is EngineGraph)
        assertEquals(ProcessRole.ENGINE, graph.identity.role)
        assertFalse(graph.businessResourcesInitialized)
        assertEquals(0, resources.openCount)
        graph.close()
        assertEquals(0, resources.openCount)
        assertClosedReportFails(graph)
    }

    @Test
    fun `isolated graph cannot receive or open main resources`() {
        val resources = CountingMainResourceFactory()
        val graph = newFactory(resources).create(testIdentity(ProcessRole.ISOLATED))

        assertTrue(graph is IsolatedGraph)
        assertEquals(ProcessRole.ISOLATED, graph.identity.role)
        assertFalse(graph.businessResourcesInitialized)
        assertEquals(0, resources.openCount)
        graph.close()
        assertEquals(0, resources.openCount)
        assertClosedReportFails(graph)
    }

    @Test
    fun `auxiliary graph stays minimal`() {
        val resources = CountingMainResourceFactory()
        val graph = newFactory(resources).create(testIdentity(ProcessRole.AUXILIARY))

        assertTrue(graph is AuxiliaryGraph)
        assertEquals(ProcessRole.AUXILIARY, graph.identity.role)
        assertFalse(graph.businessResourcesInitialized)
        assertEquals(0, resources.openCount)
        graph.close()
        assertClosedReportFails(graph)
    }

    @Test
    fun `graph ids are unique per graph construction`() {
        var next = 0
        val factory = AppGraphFactory(
            dispatchers = dispatchers,
            logger = RecordingGraphLogger(),
            graphIdFactory = { "graph-${next++}" },
        )

        val first = factory.create(testIdentity(ProcessRole.ENGINE))
        val second = factory.create(testIdentity(ProcessRole.ENGINE))

        assertNotEquals(first.graphId, second.graphId)
    }

    @Test
    fun `role specific factories reject mismatched identities`() {
        val logger = RecordingGraphLogger()
        assertThrows(IllegalArgumentException::class.java) {
            MainGraph.create(
                identity = testIdentity(ProcessRole.ENGINE),
                dispatchers = dispatchers,
                graphId = "bad-main",
                logger = logger,
                mainResourceFactory = null,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            EngineGraph.create(
                identity = testIdentity(ProcessRole.MAIN),
                dispatchers = dispatchers,
                graphId = "bad-engine",
                logger = logger,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            IsolatedGraph.create(
                identity = testIdentity(ProcessRole.MAIN),
                dispatchers = dispatchers,
                graphId = "bad-isolated",
                logger = logger,
            )
        }
    }

    private fun newFactory(
        factory: MainResourceFactory?,
    ): AppGraphFactory = AppGraphFactory(
        dispatchers = dispatchers,
        logger = RecordingGraphLogger(),
        mainResourceFactory = factory,
        graphIdFactory = { "graph-test" },
    )

    private fun assertClosedReportFails(graph: AppGraph) {
        val error = assertThrows(IllegalStateException::class.java) { graph.report() }
        assertTrue(error.message.orEmpty().contains("closed"))
    }
}
