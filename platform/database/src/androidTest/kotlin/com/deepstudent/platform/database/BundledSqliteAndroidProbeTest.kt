package com.deepstudent.platform.database

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BundledSqliteAndroidProbeTest {

    @Test
    fun deviceRunsTheBundledDriverNotSystemSqlite() {
        assertTrue(Build.VERSION.SDK_INT >= 26)
        assertEquals("androidx.sqlite:sqlite-bundled:2.6.2", report.driver.coordinate)
        assertEquals(
            "androidx.sqlite.driver.bundled.BundledSQLiteDriver",
            report.driver.implementationClass,
        )
        assertFalse(report.driver.hasConnectionPool)
        assertEquals("3.50.1", report.sqlite.version)
        assertEquals(SOURCE_ID, report.sqlite.sourceId)
        assertEquals(2, report.sqlite.threadingMode)
    }

    @Test
    fun fullProbeMatrixPassesOnDevice() {
        assertEquals(ProbeId.values().size, report.results.size)
        assertEquals(ProbeId.values().toSet(), report.results.map { it.id }.toSet())
        assertTrue(failureSummary(), report.allPassed)
        assertEquals(ProbeId.values().size, report.passedCount)
    }

    @Test
    fun targetAbiContainsArm64NativeSlice() {
        assertTrue(
            "device ABIs=${Build.SUPPORTED_ABIS.joinToString()}",
            Build.SUPPORTED_ABIS.contains("arm64-v8a"),
        )
    }

    @Test
    fun bundledNativeLibraryIsExtractedFromTheApk() {
        val nativeDir = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationInfo.nativeLibraryDir
        assertTrue(
            "missing libsqliteJni.so under $nativeDir",
            File(nativeDir, "libsqliteJni.so").isFile,
        )
    }

    @Test
    fun capabilityEvidenceIsRecordedInLogcat() {
        assertTrue(report.capabilities.fts5)
        assertTrue(report.capabilities.fts5Trigram)
        assertTrue(report.capabilities.fts5ContentlessDelete)
        assertTrue(report.capabilities.json1)
        assertTrue(report.capabilities.jsonb)
        assertTrue(report.capabilities.wal)
        assertTrue(report.capabilities.savepoint)
        assertTrue(report.capabilities.foreignKeys)

        val busy = report.result(ProbeId.WAL_BUSY_TIMEOUT)?.details.orEmpty()
        assertTrue(busy.getValue("busyMillis").toLong() in 100L..5_000L)
        assertTrue(busy["busyCode"] in setOf("5", "6"))
    }

    @Test
    fun logEventsCarryOwnerCorrelationAndDuration() {
        assertTrue(auditEvents.isNotEmpty())
        assertTrue(auditEvents.all { it.owner == report.owner })
        assertTrue(auditEvents.all { it.correlationId == report.correlationId })
        assertTrue(auditEvents.all { it.durationMillis >= 0L })
    }

    private fun failureSummary(): String =
        report.results
            .filter { it.status != ProbeStatus.PASSED }
            .joinToString { "${it.id}=${it.status}:${it.error?.message}" }

    private companion object {
        const val TAG = "S1.04-SQLiteProbe"
        const val SOURCE_ID =
            "2025-06-06 14:52:32 " +
                "b77dc5e0f596d2140d9ac682b2893ff65d3a4140aa86067a3efebe29dc914c95"

        private lateinit var report: DatabaseProbeReport
        private val auditEvents = mutableListOf<ProbeLogEvent>()

        @BeforeClass
        @JvmStatic
        fun runDeviceProbe() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            Log.i(
                TAG,
                "device=${Build.MANUFACTURER}/${Build.MODEL} api=${Build.VERSION.SDK_INT} " +
                    "abis=${Build.SUPPORTED_ABIS.joinToString()}",
            )
            report = BundledSqliteCapabilityProbe(context.cacheDir).probe(
                errorSink = { error ->
                    Log.w(
                        TAG,
                        "owner=${error.owner} correlationId=${error.correlationId} " +
                            "kind=${error.kind} diagnostic=${error.diagnostic} " +
                            "operation=${error.operation} code=${error.sqliteCode} " +
                            "message=${error.message}",
                    )
                },
                log = { event ->
                    auditEvents += event
                    Log.i(
                        TAG,
                        "owner=${event.owner} correlationId=${event.correlationId} " +
                            "probe=${event.probeId} status=${event.status} " +
                            "durationMs=${event.durationMillis} detailKeys=${event.detailKeys} " +
                            "errorKind=${event.errorKind} errorDiagnostic=${event.errorDiagnostic}",
                    )
                },
            )
            Log.i(
                TAG,
                "owner=${report.owner} correlationId=${report.correlationId} " +
                    "driver=${report.driver} sqlite=${report.sqlite.version} " +
                    "sourceId=${report.sqlite.sourceId} capabilities=${report.capabilities}",
            )
        }
    }
}
