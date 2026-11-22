package com.deepstudent.app.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class LazyProcessOwnerTest {
    @Test
    fun `opens once and closes once`() {
        val logger = RecordingGraphLogger()
        var openCount = 0
        val resource = TestMainResource("resource")
        val owner = LazyProcessOwner(
            name = "test-owner",
            logger = logger,
            opener = {
                openCount++
                resource
            },
        )

        assertFalse(owner.isInitialized)
        assertEquals(0, openCount)
        assertSame(resource, owner.get())
        assertSame(resource, owner.get())
        assertEquals(1, openCount)
        assertTrue(owner.isInitialized)

        owner.close()
        owner.close()
        assertEquals(1, resource.closeCount)
        assertFalse(owner.isInitialized)
    }

    @Test
    fun `close without open does not invoke opener`() {
        var openCount = 0
        val owner = LazyProcessOwner(
            name = "test-owner",
            logger = RecordingGraphLogger(),
            opener = {
                openCount++
                TestMainResource("resource")
            },
        )

        owner.close()

        assertEquals(0, openCount)
    }

    @Test
    fun `get after close fails explicitly`() {
        val owner = LazyProcessOwner(
            name = "test-owner",
            logger = RecordingGraphLogger(),
            opener = { TestMainResource("resource") },
        )
        owner.close()

        val error = assertThrows(IllegalStateException::class.java) { owner.get() }

        assertTrue(error.message.orEmpty().contains("test-owner"))
    }

    @Test
    fun `close failure is swallowed once and logged without raw secret`() {
        val sentinel = "SECRET_CLOSE_SENTINEL_c91e"
        val failure = IllegalStateException(sentinel)
        val resource = TestMainResource("resource", closeFailure = failure)
        val logger = RecordingGraphLogger()
        val owner = LazyProcessOwner(
            name = "test-owner",
            logger = logger,
            opener = { resource },
        )

        owner.get()
        owner.close()
        owner.close()

        assertEquals(1, resource.closeCount)
        assertEquals(1, logger.warnings.size)
        val warning = logger.warnings.single()
        val output = GraphLogFormatter.formatWarning(
            event = warning.first,
            throwable = warning.second,
            fields = warning.third,
        )
        assertTrue(output.contains("errorClass=java.lang.IllegalStateException"))
        assertFalse(output.contains(sentinel))
    }
}
