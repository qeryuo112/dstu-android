package com.deepstudent.platform.execution

import com.deepstudent.services.execution.api.ExecutionCommand
import com.deepstudent.services.execution.api.ExecutionFailureCode
import com.deepstudent.services.execution.api.ExecutionRequest
import com.deepstudent.services.execution.api.ExecutionResult
import com.deepstudent.services.execution.api.ShizukuIdentity
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuExecutionSystemTest {
    @Test
    fun noPermissionReturnsTypedFailureWithoutStartingProcess() = runBlocking {
        val transport = FakeServiceTransport(permissionGranted = false)
        val source = FakeConnectionSource().apply {
            binding = binding(transport)
        }
        val system = system(source)
        val result = system.execute(request())
        assertEquals(
            ExecutionFailureCode.PERMISSION_REQUIRED,
            (result as ExecutionResult.Failure).failure.code,
        )
        assertEquals(0, transport.requests.size)
        system.close()
    }

    @Test
    fun verifiedUidIsExposedAndRevocationAbortsRunningProcess() = runBlocking {
        val process = BlockingProcess()
        val transport = FakeServiceTransport(process = process, permissionGranted = true)
        val source = FakeConnectionSource().apply {
            this.binding = binding(transport)
        }
        val system = system(source)
        system.start()
        val running = async { system.execute(request()) }
        delay(100)
        transport.permissionGranted = false
        system.refresh()
        val result = running.await()
        assertEquals(
            ExecutionFailureCode.PERMISSION_REVOKED,
            (result as ExecutionResult.Failure).failure.code,
        )
        assertTrue(process.destroyed)
        assertEquals(1, process.closeCount)
        system.close()
    }

    @Test
    fun successfulIdentityResultUpdatesObservedUidState() = runBlocking {
        val process = FakeRemoteProcess(
            stdout = "uid=2000(shell) gid=2000(shell) context=u:r:shell:s0\n",
        )
        val source = FakeConnectionSource().apply {
            binding = binding(
                FakeServiceTransport(process = process, permissionGranted = true),
            )
        }
        val system = system(source)
        val result = system.execute(request())
        assertTrue(result is ExecutionResult.Success)
        assertEquals(ShizukuIdentity.SHELL, system.status.value.identity)
        system.close()
    }

    private fun system(source: FakeConnectionSource): ShizukuExecutionSystem {
        val log = ExecutionLogSink {}
        val authorizer = ShizukuAuthorizer(
            source = source,
            log = log,
            pollIntervalMillis = 25,
        )
        return ShizukuExecutionSystem(
            authorizer = authorizer,
            executor = ShizukuExecutor(log = log, timeoutMillis = 5_000),
        )
    }

    private fun request() = ExecutionRequest(
        taskId = "task-1",
        callerId = "unit-test",
        command = ExecutionCommand.Identity,
    )

    @Test
    fun binderDeathAbortsRunningProcessAndReleasesDescriptors() = runBlocking {
        val process = BlockingProcess()
        val transport = FakeServiceTransport(process = process, permissionGranted = true)
        val source = FakeConnectionSource().apply {
            binding = binding(transport)
        }
        val system = system(source)
        system.start()
        val running = async { system.execute(request()) }
        delay(100)
        source.bindingError = android.os.DeadObjectException()
        system.refresh()
        val result = running.await()
        assertEquals(
            ExecutionFailureCode.BINDER_DEAD,
            (result as ExecutionResult.Failure).failure.code,
        )
        assertTrue(process.destroyed)
        assertEquals(1, process.closeCount)
        system.close()
    }

    private class BlockingProcess : RemoteProcessHandle {
        private val exited = java.util.concurrent.CountDownLatch(1)
        private val stdout = java.io.PipedInputStream()
        private val stderr = java.io.PipedInputStream()
        private val stdoutWriter = java.io.PipedOutputStream(stdout)
        private val stderrWriter = java.io.PipedOutputStream(stderr)
        @Volatile
        var destroyed = false
        var closeCount = 0

        override fun stdout() = stdout

        override fun stderr() = stderr

        override fun waitFor(): Int {
            exited.await()
            return 137
        }

        override fun destroy() {
            destroyed = true
            runCatching { stdoutWriter.close() }
            runCatching { stderrWriter.close() }
            exited.countDown()
        }

        override fun close() {
            closeCount++
            runCatching { stdout.close() }
            runCatching { stderr.close() }
        }
    }
}
