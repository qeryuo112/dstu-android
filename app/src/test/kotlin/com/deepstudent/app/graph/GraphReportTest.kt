package com.deepstudent.app.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GraphReportTest {
    @Test
    fun `report carries process identity and graph state`() {
        val identity = ProcessIdentity(
            processName = "$TEST_APP_ID:engine",
            role = ProcessRole.ENGINE,
            source = ProcessNameSource.PROC_CMDLINE,
        )
        val graph = EngineGraph.create(
            identity = identity,
            dispatchers = DefaultGraphDispatchers,
            graphId = "engine-graph",
            logger = RecordingGraphLogger(),
        )

        val report = graph.report()

        assertEquals(ProcessRole.ENGINE, report.role)
        assertEquals("$TEST_APP_ID:engine", report.processName)
        assertEquals(ProcessNameSource.PROC_CMDLINE, report.processNameSource)
        assertEquals("engine-graph", report.graphId)
        assertFalse(report.businessResourcesInitialized)
    }
}
