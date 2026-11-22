package com.deepstudent.app.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphLogFormatterTest {
    @Test
    fun `warning emits only fixed throwable class and no sentinel message`() {
        val sentinel = "SECRET_SENTINEL_7f4b0c"
        val error = IllegalStateException(sentinel, IllegalArgumentException("CAUSE_$sentinel"))

        val output = GraphLogFormatter.formatWarning(
            event = "owner.close.failed",
            throwable = error,
            fields = mapOf("owner" to "main-process-resources"),
        )

        assertTrue(output.startsWith("owner.close.failed "))
        assertTrue(output.contains("errorClass=java.lang.IllegalStateException"))
        assertFalse(output.contains(sentinel))
        assertFalse(output.contains("IllegalArgumentException"))
        assertFalse(output.contains("CAUSE_"))
    }

    @Test
    fun `warning without throwable keeps caller fields only`() {
        val output = GraphLogFormatter.formatWarning(
            event = "probe.service.rejected",
            throwable = null,
            fields = mapOf("service" to "engine", "what" to 7),
        )

        assertEquals("probe.service.rejected service=engine what=7", output)
        assertFalse(output.contains("errorClass"))
    }

    @Test
    fun `format sorts fields deterministically`() {
        val output = GraphLogFormatter.format(
            event = "graph.created",
            fields = mapOf("role" to ProcessRole.ENGINE, "graphId" to "graph-1"),
        )

        assertEquals("graph.created graphId=graph-1 role=ENGINE", output)
    }
}
