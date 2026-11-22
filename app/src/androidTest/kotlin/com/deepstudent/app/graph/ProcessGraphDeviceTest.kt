package com.deepstudent.app.graph

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.DstuApplication
import com.deepstudent.app.process.EngineProbeService
import com.deepstudent.app.process.IsolatedProbeService
import com.deepstudent.app.process.ProcessProbeProtocol
import com.deepstudent.app.process.ProcessProbeReport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProcessGraphDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val application: DstuApplication
        get() = context.applicationContext as DstuApplication

    @Test
    fun mainProcessUsesOneMainGraphDuringStartupRecovery() {
        assertEquals(ProcessRole.MAIN, application.processIdentity.role)
        val first = application.mainGraph
        assertSame(first, application.mainGraph)
        assertEquals(context.packageName, application.processIdentity.processName)
        assertSame(first, application.mainGraph)
    }

    @Test
    fun engineProbeRunsInRealEngineProcessWithoutBusinessResources() {
        val report = requestReport(EngineProbeService::class.java)

        assertEquals(ProcessRole.ENGINE, report.role)
        assertEquals("engine", report.service)
        assertNotEquals(Process.myPid(), report.pid)
        assertTrue(report.processName.orEmpty().endsWith(":engine"))
        assertTrue(report.graphId.isNotBlank())
        assertFalse(report.businessResourcesInitialized)
        assertFalse(report.syncInitialized)
        assertFalse(report.securityInitialized)
        assertFalse(report.isolatedProcess)
    }

    @Test
    fun isolatedProbeRunsInIsolatedProcessWithoutBusinessResources() {
        val report = requestReport(IsolatedProbeService::class.java)

        assertEquals(ProcessRole.ISOLATED, report.role)
        assertEquals("isolated", report.service)
        assertNotEquals(Process.myPid(), report.pid)
        assertNotEquals(application.applicationInfo.uid, report.uid)
        val isolatedAppId = report.uid % USER_RANGE
        assertTrue("isolated uid was ${report.uid}", isolatedAppId in 90_000..99_999)
        if (Build.VERSION.SDK_INT >= 34) {
            assertTrue("Process.isIsolatedUid(${report.uid}) was false", Process.isIsolatedUid(report.uid))
        }
        if (Build.VERSION.SDK_INT >= 28) {
            assertTrue("Process.isIsolated() was false in the probe process", report.isolatedProcess)
        }
        assertTrue(report.graphId.isNotBlank())
        assertFalse(report.businessResourcesInitialized)
        assertFalse(report.syncInitialized)
        assertFalse(report.securityInitialized)
        assertTrue(report.processNameSource != "UNAVAILABLE")
        val isolatedName = report.processName.orEmpty()
        val expectedIsolatedPrefix = "${context.packageName}:isolated"
        assertTrue(
            "Unexpected isolated process name: ${report.processName}",
            isolatedName == "isolated" ||
                isolatedName == expectedIsolatedPrefix ||
                isolatedName.startsWith("$expectedIsolatedPrefix:"),
        )
    }

    private fun requestReport(
        serviceClass: Class<out Service>,
        timeoutMs: Long = 30_000,
    ): ProcessProbeReport {
        val session = ProbeRequestSession(serviceClass)
        val bound = context.bindService(
            Intent(context, serviceClass),
            session.connection,
            Context.BIND_AUTO_CREATE,
        )
        assertTrue("bindService returned false for ${serviceClass.name}", bound)
        try {
            return session.awaitReport(timeoutMs)
        } finally {
            session.close()
        }
    }

    private inner class ProbeRequestSession(
        private val serviceClass: Class<out Service>,
    ) {
        private val latch = CountDownLatch(1)
        private val result = AtomicReference<ProcessProbeReport?>()
        private val failure = AtomicReference<Throwable?>()

        val connection: ServiceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                handleConnected(binder)
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }

        fun awaitReport(timeoutMs: Long): ProcessProbeReport {
            assertTrue(
                "No probe report from ${serviceClass.name} within ${timeoutMs}ms",
                latch.await(timeoutMs, TimeUnit.MILLISECONDS),
            )
            failure.get()?.let { error ->
                throw AssertionError("Probe request failed for ${serviceClass.name}", error)
            }
            return checkNotNull(result.get()) {
                "Probe reply from ${serviceClass.name} had no valid report"
            }
        }

        fun close() {
            runCatching { context.unbindService(connection) }
        }

        private fun handleConnected(binder: IBinder?) {
            if (binder == null) {
                fail(AssertionError("Service returned a null binder"))
                return
            }
            try {
                val replyTo = Messenger(reportHandler())
                Messenger(binder).send(ProcessProbeProtocol.newReportRequest(replyTo))
            } catch (error: Throwable) {
                fail(error)
            }
        }

        private fun reportHandler(): Handler = Handler(
            Looper.getMainLooper(),
            Handler.Callback { message ->
                if (message.what == ProcessProbeProtocol.MSG_REPORT) {
                    result.set(ProcessProbeProtocol.fromBundle(message.data))
                    latch.countDown()
                }
                true
            },
        )

        private fun fail(error: Throwable) {
            failure.set(error)
            latch.countDown()
        }
    }

    private companion object {
        private const val USER_RANGE = 100_000
    }
}
