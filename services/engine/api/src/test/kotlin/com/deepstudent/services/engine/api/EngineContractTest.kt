package com.deepstudent.services.engine.api

import com.deepstudent.core.common.id.EngineId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EngineContractTest {
    @Test
    fun startCreatesNarrowHandleAndCancellationStillClosesIt() = runBlocking {
        val engineId = EngineId.parse("pdf")
        var closed = false
        val handle = object : EngineHandle {
            override val engineId: EngineId = engineId
            override var state: EngineState = EngineState.READY
                private set
            override val alive: Boolean get() = state != EngineState.CLOSED

            override suspend fun stop() {
                state = EngineState.CLOSED
                closed = true
            }

            override fun close() {
                state = EngineState.CLOSED
                closed = true
            }
        }
        val engine = Engine { handle }
        val started = engine.start()
        assertEquals(engineId, started.engineId)
        try {
            started.withOpen<Unit> { throw CancellationException("stop") }
            fail("expected cancellation")
        } catch (expected: CancellationException) {
            assertEquals("stop", expected.message)
        }
        assertTrue(closed)
        assertFalse(started.alive)
        assertEquals(EngineState.CLOSED, started.state)
    }

    @Test
    fun descriptorCarriesOnlyExplicitCapabilities() {
        val descriptor = EngineDescriptor(
            engineId = EngineId.parse("fsrs"),
            version = "1",
            capabilities = setOf("schedule", "train"),
        )
        assertEquals(setOf("schedule", "train"), descriptor.capabilities)
    }
}
