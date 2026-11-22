package com.deepstudent.platform.execution

import com.deepstudent.services.execution.api.ExecutionCommand
import com.deepstudent.services.execution.api.ExecutionFailureCode
import com.deepstudent.services.execution.api.ExecutionRequest
import com.deepstudent.services.execution.api.ExecutionResult
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuOwnershipTest {
    @Test
    fun cancellationDuringNewProcessReleasesTheLateReplyExactlyOnce() = runBlocking {
        lateReply { running, _ -> running.cancelAndJoin() }
    }

    @Test
    fun closeDuringNewProcessReleasesTheLateReplyAndRejectsNewWork() = runBlocking {
        lateReply { running, executor ->
            executor.close()
            assertEquals(ExecutionFailureCode.SYSTEM_CLOSED, running.await().failureCode())
        }
    }

    @Test
    fun revocationDuringNewProcessOwnsAndReleasesTheLateReply() = runBlocking {
        lateReply { running, executor ->
            executor.abortAll(ExecutionFailureCode.PERMISSION_REVOKED)
            assertEquals(ExecutionFailureCode.PERMISSION_REVOKED, running.await().failureCode())
        }
    }

    @Test
    fun timeoutIncludesNewProcessAndReleasesItsLateReply() = runBlocking {
        lateReply(timeoutMillis = 150) { running, _ ->
            assertEquals(ExecutionFailureCode.TIMEOUT, running.await().failureCode())
        }
    }

    @Test
    fun closeIsTerminalAndOldAuthorizationCheckpointCannotStartAProcess() = runBlocking {
        val transport = FakeServiceTransport()
        val executor = ShizukuExecutor(log = {})
        try {
            val checkpoint = executor.checkpoint()
            executor.abortAll(ExecutionFailureCode.PERMISSION_REVOKED)
            val stale = executor.execute(request(), binding(transport), checkpoint)
            assertEquals(ExecutionFailureCode.PERMISSION_REVOKED, stale.failureCode())
            executor.close()
            assertEquals(ExecutionFailureCode.SYSTEM_CLOSED, executor.execute(request(), binding(transport)).failureCode())
            assertTrue(transport.requests.isEmpty())
        } finally {
            executor.close()
        }
    }

    @Test
    fun freshPermissionChecksRejectRevocationBeforeStartAndAfterExit() = runBlocking {
        val transport = FakeServiceTransport(permissionGranted = false)
        val executor = ShizukuExecutor(log = {})
        try {
            val result = executor.execute(request(), binding(transport))
            assertEquals(ExecutionFailureCode.PERMISSION_REVOKED, result.failureCode())
            assertTrue(transport.requests.isEmpty())
            transport.permissionGranted = true
            transport.process = FakeRemoteProcess(
                stdout = "uid=2000(shell)\n",
                onWait = { transport.permissionGranted = false },
            )
            assertEquals(ExecutionFailureCode.PERMISSION_REVOKED, executor.execute(request(), binding(transport)).failureCode())
        } finally {
            executor.close()
        }
    }

    @Test
    fun streamReadFailureIsNotAReceiptAndClosesTheOwner() = runBlocking {
        val process = BrokenReadProcess()
        val executor = ShizukuExecutor(log = {}, timeoutMillis = 500)
        try {
            val transport = FakeServiceTransport(process = process)
            val result = executor.execute(request(), binding(transport))
            assertEquals(ExecutionFailureCode.IO_FAILED, result.failureCode())
            assertEquals(1, process.closes.get())
            assertEquals(1, process.destroys.get())
        } finally {
            executor.close()
        }
    }

    @Test
    fun rootUidIsReportedWithoutPretendingToBeShellAndLogsExcludeSecrets() = runBlocking {
        val events = mutableListOf<ExecutionLogRecord>()
        val executor = ShizukuExecutor(log = { events += it })
        val transport = FakeServiceTransport(uid = 0, process = FakeRemoteProcess("uid=0(root) secret-output\n"))
        try {
            val result = executor.execute(
                ExecutionRequest("secret-task", "secret-caller", ExecutionCommand.Identity),
                binding(transport, uid = 0),
            ) as ExecutionResult.Success
            assertEquals(0, result.receipt.identity.observedUid)
            assertTrue(events.filterIsInstance<ExecutionLogEvent>().any { it.kind == "process_exit" })
            val logs = events.joinToString("\n") { it.format() }
            assertFalse(logs.contains("secret-"))
            assertFalse(logs.contains("uid=0(root)"))
            assertTrue(logs.contains(executionCorrelation("secret-task")))
        } finally {
            executor.close()
        }
    }

    private suspend fun lateReply(
        timeoutMillis: Long = 3_000,
        terminate: suspend (kotlinx.coroutines.Deferred<ExecutionResult>, ShizukuExecutor) -> Unit,
    ) = kotlinx.coroutines.coroutineScope {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val process = CountingProcess()
        val transport = FakeServiceTransport().apply {
            processFactory = {
                entered.countDown()
                awaitUninterruptibly(release)
                process
            }
        }
        val executor = ShizukuExecutor(log = {}, timeoutMillis = timeoutMillis)
        val running = async { executor.execute(request(), binding(transport)) }
        try {
            withTimeout(2_000) { while (entered.count != 0L) delay(5) }
            withTimeout(2_000) { terminate(running, executor) }
        } finally {
            release.countDown()
            executor.close()
            running.cancelAndJoin()
        }
        withTimeout(2_000) { while (process.closes.get() == 0) delay(5) }
        assertEquals(1, process.destroys.get())
        assertEquals(1, process.closes.get())
    }

    private fun awaitUninterruptibly(latch: CountDownLatch) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (latch.count != 0L && System.nanoTime() < deadline) {
            try {
                latch.await(20, TimeUnit.MILLISECONDS)
            } catch (ignored: InterruptedException) {
                // Binder transactions can finish after their waiting Future was cancelled.
            }
        }
        check(latch.count == 0L)
    }

    private fun request() = ExecutionRequest("ownership-test", "unit-test", ExecutionCommand.Identity)
    private fun ExecutionResult.failureCode() = (this as ExecutionResult.Failure).failure.code

    private open class CountingProcess : RemoteProcessHandle {
        val closes = AtomicInteger()
        val destroys = AtomicInteger()
        override fun stdout(): InputStream = ByteArrayInputStream("uid=2000(shell)\n".toByteArray())
        override fun stderr(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = 0
        override fun destroy() { destroys.incrementAndGet() }
        override fun close() { closes.incrementAndGet() }
    }

    private class BrokenReadProcess : CountingProcess() {
        override fun stdout(): InputStream = object : InputStream() {
            override fun read(): Int = throw IOException("private error text")
        }
    }
}
