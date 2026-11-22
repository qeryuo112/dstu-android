package com.deepstudent.platform.execution

import android.os.Binder
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deepstudent.services.execution.api.ExecutionCommand
import com.deepstudent.services.execution.api.ExecutionFailureCode
import com.deepstudent.services.execution.api.ExecutionRequest
import com.deepstudent.services.execution.api.ExecutionResult
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.shizuku.server.IShizukuService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShizukuPfdAdapterDeviceTest {
    @get:Rule
    val foreground = ExecutionForegroundRule()

    @Test
    fun realPfdAdapterDrainsBothFullPipesWithBoundedCaptureAndNoFdGrowth(): Unit = runBlocking {
        // The foreground host initializes Binder/JIT/render resources asynchronously.
        // Warm the exact adapter path before measuring its owned pipe descriptors.
        PfdRemoteProcessFixture().use { remote ->
            val executor = ShizukuExecutor(log = {})
            try { assertTrue(executor.execute(request(), binding(remote)) is ExecutionResult.Success) }
            finally { executor.close() }
        }
        val before = pipeDescriptorCount()
        repeat(4) {
            PfdRemoteProcessFixture(
                payload = ("OUT-" + "a".repeat(128 * 1024)).toByteArray(),
                error = ("ERR-" + "b".repeat(128 * 1024)).toByteArray(),
            ).use { remote ->
                val executor = ShizukuExecutor(log = {}, maxCaptureBytes = 1024, timeoutMillis = 5_000)
                try {
                    val receipt = (executor.execute(request(), binding(remote)) as ExecutionResult.Success).receipt
                    assertEquals(1024, receipt.output.stdout.length)
                    assertEquals(1024, receipt.output.stderr.length)
                    assertTrue(receipt.output.stdoutTruncated)
                    assertTrue(receipt.output.stderrTruncated)
                    assertTrue(receipt.output.stdout.startsWith("OUT-"))
                    assertTrue(receipt.output.stderr.startsWith("ERR-"))
                    assertClosedDescriptors(remote)
                } finally {
                    executor.close()
                }
            }
        }
        val after = pipeDescriptorCount()
        assertEquals("owned pipe descriptor baseline grew", before, after)
        android.util.Log.i("DstuExecutionEvidence", "event=pfd-baseline before=$before after=$after rounds=4")
    }

    @Test
    fun stdinWriterClosesImmediatelyAndTheChildObservesEof() {
        PfdRemoteProcessFixture().use { remote ->
            val handle = AndroidShizukuTransport(fakeService(remote)).newProcess(arrayOf("pm", "list", "packages"))
            try {
                assertEquals(-1, remote.stdinFd())
                assertTrue("child stdin did not receive EOF", remote.stdinEof.await(2, TimeUnit.SECONDS))
            } finally {
                handle.close()
            }
            assertClosedDescriptors(remote)
        }
    }

    @Test
    fun missingStderrClosesPreviouslyAcquiredDescriptorsAndDestroysTheProcess() {
        PfdRemoteProcessFixture(omitStderr = true).use { remote ->
            val error = runCatching {
                AndroidShizukuTransport(fakeService(remote)).newProcess(arrayOf("pm", "list", "packages"))
            }.exceptionOrNull()
            assertTrue(error is IOException)
            assertTrue(remote.destroyed.get())
            assertEquals(-1, remote.stdinFd())
            assertEquals(-1, remote.stdoutFd())
        }
    }

    @Test
    fun timeoutDestroysAndClosesActualAndroidPipes() = runBlocking {
        PfdRemoteProcessFixture(holdOpen = true).use { remote ->
            val executor = ShizukuExecutor(log = {}, timeoutMillis = 300)
            try {
                val result = executor.execute(request(), binding(remote)) as ExecutionResult.Failure
                assertEquals(ExecutionFailureCode.TIMEOUT, result.failure.code)
                awaitReleased(remote)
                assertClosedDescriptors(remote)
            } finally {
                executor.close()
            }
        }
    }

    @Test
    fun callerCancellationDestroysAndClosesActualAndroidPipes() = runBlocking {
        PfdRemoteProcessFixture(holdOpen = true).use { remote ->
            val executor = ShizukuExecutor(log = {}, timeoutMillis = 5_000)
            val running = async { executor.execute(request(), binding(remote)) }
            try {
                withTimeout(2_000) { while (remote.stderrGets.get() == 0) delay(5) }
                running.cancelAndJoin()
                awaitReleased(remote)
                assertClosedDescriptors(remote)
            } finally {
                executor.close()
                running.cancelAndJoin()
            }
        }
    }

    @Test
    fun aBinderStartFailureIsAFailureNotASkip() = runBlocking {
        val executor = ShizukuExecutor(log = {}, timeoutMillis = 1_000)
        try {
            val service = fakeService(failStart = true)
            val result = executor.execute(request(), binding(AndroidShizukuTransport(service))) as ExecutionResult.Failure
            assertEquals(ExecutionFailureCode.BINDER_DEAD, result.failure.code)
        } finally {
            executor.close()
        }
    }

    private suspend fun awaitReleased(remote: PfdRemoteProcessFixture) {
        withTimeout(2_000) {
            while (!remote.destroyed.get() || remote.stdoutFd() != -1 || remote.stderrFd() != -1) delay(5)
        }
    }

    private fun assertClosedDescriptors(remote: PfdRemoteProcessFixture) {
        assertEquals(-1, remote.stdinFd())
        assertEquals(-1, remote.stdoutFd())
        assertEquals(-1, remote.stderrFd())
        assertEquals(1, remote.stdinGets.get())
        assertEquals(1, remote.stdoutGets.get())
        assertEquals(1, remote.stderrGets.get())
    }

    private fun binding(remote: PfdRemoteProcessFixture) = binding(AndroidShizukuTransport(fakeService(remote)))

    private fun binding(transport: ShizukuServiceTransport) = ShizukuBinding(
        bindingId = "real-pfd-device",
        backendUid = 2000,
        serviceVersion = 13,
        isAlive = { true },
        transport = transport,
    )

    private fun request() = ExecutionRequest("pfd-device", "androidTest", ExecutionCommand.PackageList)
    private fun pipeDescriptorCount(): Int = checkNotNull(File("/proc/self/fd").listFiles()).count { fd ->
        try { java.nio.file.Files.readSymbolicLink(fd.toPath()).toString().startsWith("pipe:[") }
        catch (_: java.nio.file.NoSuchFileException) { false }
    }

    private fun fakeService(remote: PfdRemoteProcessFixture? = null, failStart: Boolean = false): IShizukuService {
        val binder = Binder()
        val values: Map<String, Any> = mapOf(
            "asBinder" to binder,
            "getUid" to 2000,
            "getVersion" to 13,
            "checkSelfPermission" to true,
            "toString" to "FakeShizukuService",
            "hashCode" to System.identityHashCode(binder),
            "equals" to false,
        )
        return Proxy.newProxyInstance(IShizukuService::class.java.classLoader, arrayOf(IShizukuService::class.java)) { _, method, args ->
            if (method.name == "newProcess") {
                assertEquals(listOf("pm", "list", "packages"), (args!![0] as Array<*>).toList())
                if (failStart) throw android.os.RemoteException("binder died")
                checkNotNull(remote)
            } else {
                values.getValue(method.name)
            }
        } as IShizukuService
    }
}
