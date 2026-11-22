package com.deepstudent.platform.execution

import com.deepstudent.services.execution.api.ExecutionCommand
import com.deepstudent.services.execution.api.ExecutionFailureCode
import com.deepstudent.services.execution.api.ExecutionRequest
import com.deepstudent.services.execution.api.ExecutionResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuSystemOwnershipTest {
    @Test
    fun stopDoesNotRemovePermissionMonitoringFromAnActiveExecution() = runBlocking {
        val waitEntered = CountDownLatch(1)
        val process = FakeRemoteProcess(stdout = "uid=2000(shell)\n", onWait = {
            waitEntered.countDown()
            CountDownLatch(1).await(5, TimeUnit.SECONDS)
        })
        val transport = FakeServiceTransport(process = process)
        val source = FakeConnectionSource().apply { binding = binding(transport) }
        val system = ShizukuExecutionSystem(
            ShizukuAuthorizer(source, log = {}, pollIntervalMillis = 25),
            ShizukuExecutor(log = {}),
        )
        val running = async { system.execute(request()) }
        try {
            withTimeout(2_000) { while (waitEntered.count != 0L) delay(5) }
            system.stop()
            assertTrue(source.listeners.isNotEmpty())
            transport.permissionGranted = false
            val result = withTimeout(2_000) { running.await() } as ExecutionResult.Failure
            assertEquals(ExecutionFailureCode.PERMISSION_REVOKED, result.failure.code)
            assertEquals(1, process.destroyCount)
            assertEquals(1, process.closeCount)
            assertTrue(source.listeners.isEmpty())
        } finally {
            system.close()
            running.cancelAndJoin()
        }
    }

    @Test
    fun aReadyToReadyBinderReplacementCannotFinishAnOldExecution() = runBlocking {
        val waitEntered = CountDownLatch(1)
        val process = FakeRemoteProcess(stdout = "uid=2000(shell)\n", onWait = {
            waitEntered.countDown()
            CountDownLatch(1).await(5, TimeUnit.SECONDS)
        })
        val source = FakeConnectionSource().apply { binding = binding(FakeServiceTransport(process = process)) }
        val system = ShizukuExecutionSystem(
            ShizukuAuthorizer(source, log = {}, pollIntervalMillis = 10_000),
            ShizukuExecutor(log = {}),
        )
        val running = async { system.execute(request()) }
        try {
            withTimeout(2_000) { while (waitEntered.count != 0L) delay(5) }
            source.binding = binding(FakeServiceTransport(), id = "new-server-same-uid")
            system.refresh()
            assertTrue(system.status.value.canExecute)
            val result = withTimeout(2_000) { running.await() } as ExecutionResult.Failure
            assertEquals(ExecutionFailureCode.BINDER_DEAD, result.failure.code)
            assertEquals(null, system.status.value.observedUid)
            assertEquals(1, process.closeCount)
        } finally {
            system.close()
            running.cancelAndJoin()
        }
    }

    private fun request() = ExecutionRequest("system-owner", "unit-test", ExecutionCommand.Identity)
}
