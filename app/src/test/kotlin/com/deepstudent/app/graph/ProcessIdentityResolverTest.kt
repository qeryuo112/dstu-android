package com.deepstudent.app.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessIdentityResolverTest {
    @Test
    fun `api 26 uses proc cmdline and never application getProcessName`() {
        val platform = FakePlatform(
            sdkInt = 26,
            procCmdline = "$TEST_APP_ID:engine",
        )

        val identity = resolveProcessIdentity(
            applicationId = TEST_APP_ID,
            platform = platform,
            processUid = 10_123,
            appUid = 10_123,
        )

        assertEquals(ProcessRole.ENGINE, identity.role)
        assertEquals(ProcessNameSource.PROC_CMDLINE, identity.source)
        assertEquals("$TEST_APP_ID:engine", identity.processName)
        assertEquals(0, platform.applicationNameCalls)
        assertEquals(1, platform.procCalls)
    }

    @Test
    fun `api 28 prefers application process name`() {
        val platform = FakePlatform(
            sdkInt = 28,
            applicationName = "$TEST_APP_ID:isolated",
        )

        val identity = resolveProcessIdentity(
            applicationId = TEST_APP_ID,
            platform = platform,
            processUid = 10_123,
            appUid = 10_123,
        )

        assertEquals(ProcessRole.ISOLATED, identity.role)
        assertEquals(ProcessNameSource.APPLICATION_API, identity.source)
        assertEquals(0, platform.procCalls)
    }

    @Test
    fun `api 28 falls back to proc when application name is blank`() {
        val platform = FakePlatform(
            sdkInt = 28,
            applicationName = "",
            procCmdline = TEST_APP_ID,
        )

        val identity = resolveProcessIdentity(
            applicationId = TEST_APP_ID,
            platform = platform,
            processUid = 10_123,
            appUid = 10_123,
        )

        assertEquals(ProcessRole.MAIN, identity.role)
        assertEquals(ProcessNameSource.PROC_CMDLINE, identity.source)
    }

    @Test
    fun `isolated uid forces isolated role when names are unavailable`() {
        val platform = FakePlatform(sdkInt = 26)

        val identity = resolveProcessIdentity(
            applicationId = TEST_APP_ID,
            platform = platform,
            processUid = 91_234,
            appUid = 10_123,
        )

        assertEquals(ProcessRole.ISOLATED, identity.role)
        assertEquals(ProcessNameSource.UNAVAILABLE, identity.source)
        assertTrue(identity.isIsolatedUid)
    }

    @Test
    fun `isolated uid overrides a misleading main process name`() {
        val platform = FakePlatform(
            sdkInt = 28,
            applicationName = TEST_APP_ID,
        )

        val identity = resolveProcessIdentity(
            applicationId = TEST_APP_ID,
            platform = platform,
            processUid = 90_555,
            appUid = 10_123,
        )

        assertEquals(ProcessRole.ISOLATED, identity.role)
        assertTrue(identity.isIsolatedUid)
    }

    @Test
    fun `ordinary different uid is not treated as isolated`() {
        val platform = FakePlatform(
            sdkInt = 28,
            applicationName = TEST_APP_ID,
        )

        val identity = resolveProcessIdentity(
            applicationId = TEST_APP_ID,
            platform = platform,
            processUid = 10_200,
            appUid = 10_123,
        )

        assertEquals(ProcessRole.MAIN, identity.role)
        assertFalse(identity.isIsolatedUid)
        assertFalse(isIsolatedUid(processUid = 10_200, appUid = 10_123))
    }

    @Test
    fun `isolated uid check accounts for multi-user uid offsets`() {
        assertTrue(isIsolatedUid(processUid = 1_090_123, appUid = 1_010_123))
        assertTrue(isIsolatedUid(processUid = 12_091_234, appUid = 12_010_123))
        assertFalse(isIsolatedUid(processUid = 1_090_123, appUid = 10_123))
        assertFalse(isIsolatedUid(processUid = 1_010_200, appUid = 1_010_123))
    }

    @Test
    fun `platform isolated flag overrides an unknown process name`() {
        val platform = FakePlatform(sdkInt = 28)

        val identity = resolveProcessIdentity(
            applicationId = TEST_APP_ID,
            platform = platform,
            processUid = 91_234,
            appUid = 91_234,
            processIsIsolated = true,
        )

        assertEquals(ProcessRole.ISOLATED, identity.role)
        assertTrue(identity.isIsolatedUid)
    }

    @Test
    fun `unavailable names in unknown process produce auxiliary role`() {
        val platform = FakePlatform(sdkInt = 26, procCmdline = "")

        val identity = resolveProcessIdentity(
            applicationId = TEST_APP_ID,
            platform = platform,
            processUid = 10_123,
            appUid = 10_123,
        )

        assertEquals(ProcessRole.AUXILIARY, identity.role)
        assertEquals(ProcessNameSource.UNAVAILABLE, identity.source)
    }

    private class FakePlatform(
        override val sdkInt: Int,
        private val applicationName: String? = null,
        private val procCmdline: String? = null,
    ) : ProcessNamePlatform {
        var applicationNameCalls: Int = 0
            private set
        var procCalls: Int = 0
            private set

        override fun applicationProcessName(): String? {
            applicationNameCalls++
            return applicationName
        }

        override fun procCmdline(): String? {
            procCalls++
            return procCmdline
        }
    }
}
