package com.deepstudent.platform.execution

import com.deepstudent.services.execution.api.ExecutionCommand
import com.deepstudent.services.execution.api.ExecutionFailureCode
import com.deepstudent.services.execution.api.ExecutionRequest
import com.deepstudent.services.execution.api.ExecutionResult
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuExecutorTest {
    @Test
    fun idAndPackageListUseFixedArgvAndCarryBackendUid() = runBlocking {
        val idProcess = FakeRemoteProcess(
            stdout = "uid=2000(shell) gid=2000(shell) context=u:r:shell:s0\n",
        )
        val idTransport = FakeServiceTransport(process = idProcess)
        val executor = ShizukuExecutor(log = {}, timeoutMillis = 1_000)
        val idResult = executor.execute(request(ExecutionCommand.Identity), binding(idTransport))
        val idReceipt = (idResult as ExecutionResult.Success).receipt
        assertEquals(2000, idReceipt.identity.backendUid)
        assertEquals(2000, idReceipt.identity.observedUid)
        assertEquals(listOf("id"), idTransport.requests.single())
        assertEquals(1, idProcess.closeCount)

        val pmProcess = FakeRemoteProcess(stdout = "package:android\npackage:com.deepstudent.app\n")
        val pmTransport = FakeServiceTransport(process = pmProcess)
        val pmResult = executor.execute(
            request(ExecutionCommand.PackageList),
            binding(pmTransport),
        )
        val pmReceipt = (pmResult as ExecutionResult.Success).receipt
        assertEquals(listOf("pm", "list", "packages"), pmTransport.requests.single())
        assertEquals(null, pmReceipt.identity.observedUid)
        assertEquals(2000, pmReceipt.identity.backendUid)
        executor.close()
    }

    @Test
    fun rootOutputCannotMasqueradeAsShellBackend() = runBlocking {
        val transport = FakeServiceTransport(
            process = FakeRemoteProcess(
                stdout = "uid=0(root) gid=0(root) context=u:r:su:s0\n",
            ),
        )
        val executor = ShizukuExecutor(log = {}, timeoutMillis = 1_000)
        val result = executor.execute(request(ExecutionCommand.Identity), binding(transport))
        assertEquals(
            ExecutionFailureCode.UID_MISMATCH,
            (result as ExecutionResult.Failure).failure.code,
        )
        executor.close()
    }

    @Test
    fun untrustedBackendUidAndInvalidAuditFailBeforeProcessStart() = runBlocking {
        val transport = FakeServiceTransport(uid = 10123)
        val executor = ShizukuExecutor(log = {}, timeoutMillis = 1_000)
        val untrusted = executor.execute(
            request(ExecutionCommand.Identity),
            binding(transport, uid = 10123),
        )
        assertEquals(
            ExecutionFailureCode.UNTRUSTED_BACKEND_UID,
            (untrusted as ExecutionResult.Failure).failure.code,
        )
        assertTrue(transport.requests.isEmpty())

        val invalid = executor.execute(
            ExecutionRequest(" ", "caller", ExecutionCommand.Identity),
            binding(transport),
        )
        assertEquals(
            ExecutionFailureCode.INVALID_REQUEST,
            (invalid as ExecutionResult.Failure).failure.code,
        )
        executor.close()
    }

    @Test
    fun bothStreamsDrainConcurrentlyWhenTheirPipesFill() = runBlocking {
        val process = PipeRemoteProcess(payloadBytes = 128 * 1024)
        val transport = FakeServiceTransport(process = process)
        val executor = ShizukuExecutor(
            maxCaptureBytes = 1024,
            timeoutMillis = 5_000,
            log = {},
        )
        val result = executor.execute(request(ExecutionCommand.PackageList), binding(transport))
        val receipt = (result as ExecutionResult.Success).receipt
        assertTrue(receipt.output.stdoutTruncated)
        assertTrue(receipt.output.stderrTruncated)
        assertTrue(receipt.output.stdout.length <= 1024)
        assertTrue(receipt.output.stderr.length <= 1024)
        assertTrue(process.stdoutDrained.await(1, TimeUnit.SECONDS))
        assertTrue(process.stderrDrained.await(1, TimeUnit.SECONDS))
        assertEquals(1, process.closeCount)
        executor.close()
    }

    @Test
    fun timeoutDestroysAndClosesOwnedProcess() = runBlocking {
        val process = NeverExitingProcess()
        val transport = FakeServiceTransport(process = process)
        val executor = ShizukuExecutor(log = {}, timeoutMillis = 100)
        val result = executor.execute(request(ExecutionCommand.Identity), binding(transport))
        assertEquals(
            ExecutionFailureCode.TIMEOUT,
            (result as ExecutionResult.Failure).failure.code,
        )
        assertTrue(process.destroyCount >= 1)
        assertEquals(1, process.closeCount)
        executor.close()
    }

    @Test
    fun abortAllInterruptsOwnedProcessAndReportsConnectionLoss() = runBlocking {
        val process = NeverExitingProcess()
        val transport = FakeServiceTransport(process = process)
        val executor = ShizukuExecutor(log = {}, timeoutMillis = 10_000)
        val running = async {
            executor.execute(request(ExecutionCommand.Identity), binding(transport))
        }
        delay(100)
        executor.abortAll(ExecutionFailureCode.BINDER_DEAD)
        val result = running.await()
        assertEquals(
            ExecutionFailureCode.BINDER_DEAD,
            (result as ExecutionResult.Failure).failure.code,
        )
        assertTrue(process.destroyCount >= 1)
        assertEquals(1, process.closeCount)
        executor.close()
    }

    @Test
    fun cancellingTheCallerDestroysAndClosesTheOwnedProcess() = runBlocking {
        val process = NeverExitingProcess()
        val transport = FakeServiceTransport(process = process)
        val executor = ShizukuExecutor(log = {}, timeoutMillis = 10_000)
        val running = async {
            executor.execute(request(ExecutionCommand.Identity), binding(transport))
        }
        delay(100)
        running.cancelAndJoin()
        assertTrue(process.destroyCount >= 1)
        assertEquals(1, process.closeCount)
        executor.close()
    }

    private fun request(command: ExecutionCommand) =
        ExecutionRequest("task-1", "unit-test", command)

    private class PipeRemoteProcess(payloadBytes: Int) : RemoteProcessHandle {
        private val stdoutPipe = PipedInputStream(8 * 1024)
        private val stderrPipe = PipedInputStream(8 * 1024)
        private val stdoutWriter = PipedOutputStream(stdoutPipe)
        private val stderrWriter = PipedOutputStream(stderrPipe)
        private val exited = CountDownLatch(1)
        val stdoutDrained = CountDownLatch(1)
        val stderrDrained = CountDownLatch(1)
        private val output = object : InputStream() {
            override fun read(): Int = stdoutPipe.read()

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                stdoutPipe.read(buffer, offset, length)

            override fun close() {
                stdoutPipe.close()
            }
        }
        private val error = object : InputStream() {
            override fun read(): Int = stderrPipe.read()

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                stderrPipe.read(buffer, offset, length)

            override fun close() {
                stderrPipe.close()
            }
        }
        var closeCount = 0
        private val payload = ByteArray(payloadBytes) { (it % 23 + 65).toByte() }

        init {
            Thread {
                runCatching { stdoutWriter.use { it.write(payload) } }
                stdoutDrained.countDown()
            }.apply { isDaemon = true; start() }
            Thread {
                runCatching { stderrWriter.use { it.write(payload) } }
                stderrDrained.countDown()
            }.apply { isDaemon = true; start() }
            Thread {
                stdoutDrained.await()
                stderrDrained.await()
                exited.countDown()
            }.apply { isDaemon = true; start() }
        }

        override fun stdout(): InputStream = output

        override fun stderr(): InputStream = error

        override fun waitFor(): Int {
            exited.await(10, TimeUnit.SECONDS)
            return 0
        }

        override fun destroy() {
            runCatching { stdoutWriter.close() }
            runCatching { stderrWriter.close() }
            exited.countDown()
        }

        override fun close() {
            closeCount++
            runCatching { output.close() }
            runCatching { error.close() }
        }
    }

    private class NeverExitingProcess : RemoteProcessHandle {
        private val stdoutPipe = PipedInputStream()
        private val stderrPipe = PipedInputStream()
        private val stdoutWriter = PipedOutputStream(stdoutPipe)
        private val stderrWriter = PipedOutputStream(stderrPipe)
        private val exited = CountDownLatch(1)
        var destroyCount = 0
        var closeCount = 0

        override fun stdout(): InputStream = stdoutPipe

        override fun stderr(): InputStream = stderrPipe

        override fun waitFor(): Int {
            exited.await()
            return 137
        }

        override fun destroy() {
            destroyCount++
            runCatching { stdoutWriter.close() }
            runCatching { stderrWriter.close() }
            exited.countDown()
        }

        override fun close() {
            closeCount++
            runCatching { stdoutPipe.close() }
            runCatching { stderrPipe.close() }
        }
    }
}
