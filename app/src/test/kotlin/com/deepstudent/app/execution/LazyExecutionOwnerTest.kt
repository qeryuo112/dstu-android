package com.deepstudent.app.execution

import com.deepstudent.app.graph.ProcessRole
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.services.execution.api.ExecutionFailure
import com.deepstudent.services.execution.api.ExecutionFailureCode
import com.deepstudent.services.execution.api.ExecutionRequest
import com.deepstudent.services.execution.api.ExecutionResult
import com.deepstudent.services.execution.api.ExecutionSystem
import com.deepstudent.services.execution.api.ShizukuStatus
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LazyExecutionOwnerTest {
    @Test fun concurrentCallersOpenOnceAndShareTheSystem() = runTest {
        val opens = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val system = TestExecutionSystem()
        val owner = LazyExecutionOwner({ ProcessRole.MAIN }) {
            opens.incrementAndGet()
            entered.complete(Unit)
            gate.await()
            system
        }
        assertFalse(owner.isInitialized)
        val callers = List(32) { async(Dispatchers.Default) { owner.get() } }
        entered.await()
        assertEquals(1, opens.get())
        gate.complete(Unit)
        callers.awaitAll().forEach { assertSame(system, it) }
        assertTrue(owner.isInitialized)
        owner.close()
        owner.close()
        assertEquals(1, system.closes.get())
        assertFalse(owner.isInitialized)
        assertClosed(owner)
    }

    @Test fun closeWaitsForOpeningAndPermanentlyClosesTheResult() = runTest {
        val gate = CompletableDeferred<Unit>()
        val system = TestExecutionSystem()
        val owner = LazyExecutionOwner({ ProcessRole.MAIN }) { gate.await(); system }
        val opening = async { owner.get() }
        runCurrent()
        val closing = async { owner.close() }
        runCurrent()
        assertFalse(closing.isCompleted)
        gate.complete(Unit)
        assertSame(system, opening.await())
        closing.await()
        assertEquals(1, system.closes.get())
        assertClosed(owner)
    }

    @Test fun failedAndCancelledOpenCanRetryWithoutLeakingInitialization() = runTest {
        val gate = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val system = TestExecutionSystem()
        val logs = RecordingLogger()
        val owner = LazyExecutionOwner({ ProcessRole.MAIN }, logs) {
            when (attempts.incrementAndGet()) {
                1 -> error("sensitive diagnostic must not be logged")
                2 -> { gate.await(); system }
                else -> system
            }
        }
        try { owner.get(); fail("first attempt must fail") } catch (_: IllegalStateException) { }
        assertFalse(owner.isInitialized)
        val cancelled = async { owner.get() }
        runCurrent()
        cancelled.cancelAndJoin()
        assertFalse(owner.isInitialized)
        assertSame(system, owner.get())
        owner.close()
        assertEquals(3, attempts.get())
        assertEquals(listOf("execution.owner.open_failed", "execution.owner.opened", "execution.owner.closed"),
            logs.records.map { it.event })
        assertTrue(logs.records.all { it.error == null && it.fields.toString().contains("sensitive").not() })
    }

    @Test fun unusedOrNonMainOwnersNeverOpenTheFactory() = runTest {
        val opens = AtomicInteger()
        for (role in listOf(ProcessRole.ENGINE, ProcessRole.ISOLATED, ProcessRole.AUXILIARY)) {
            val owner = LazyExecutionOwner({ role }) { opens.incrementAndGet(); TestExecutionSystem() }
            try { owner.get(); fail("$role must be rejected") } catch (_: IllegalStateException) { }
            assertFalse(owner.isInitialized)
            owner.close()
        }
        val unused = LazyExecutionOwner({ ProcessRole.MAIN }) { opens.incrementAndGet(); TestExecutionSystem() }
        unused.close()
        assertFalse(unused.isInitialized)
        assertClosed(unused)
        assertEquals(0, opens.get())
    }

    @Test fun failedCloseIsTerminalAndIsNotRetried() = runTest {
        val system = TestExecutionSystem(failClose = true)
        val owner = LazyExecutionOwner({ ProcessRole.MAIN }) { system }
        owner.get()
        try { owner.close(); fail("close must fail") } catch (_: IllegalStateException) { }
        assertFalse(owner.isInitialized)
        owner.close()
        assertEquals(1, system.closes.get())
        assertClosed(owner)
    }

    private suspend fun assertClosed(owner: LazyExecutionOwner) {
        try { owner.get(); fail("closed owner must not reopen") } catch (_: IllegalStateException) { }
    }
}

private class RecordingLogger : Logger {
    val records = mutableListOf<LogRecord>()
    override fun log(record: LogRecord) { records += record }
}

private class TestExecutionSystem(private val failClose: Boolean = false) : ExecutionSystem {
    override val status = MutableStateFlow(ShizukuStatus())
    val closes = AtomicInteger()
    override fun start() = Unit
    override fun stop() = Unit
    override suspend fun refresh() = Unit
    override fun requestPermission(onResult: (Boolean) -> Unit) = onResult(false)
    override suspend fun execute(request: ExecutionRequest): ExecutionResult =
        ExecutionResult.Failure(ExecutionFailure(ExecutionFailureCode.SYSTEM_CLOSED))
    override fun close() {
        closes.incrementAndGet()
        check(!failClose) { "failure deliberately contains no credentials" }
    }
}
