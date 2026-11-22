package com.deepstudent.app.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessClassifierTest {
    @Test
    fun `classifies main engine isolated and auxiliary names`() {
        assertEquals(
            ProcessRole.MAIN,
            ProcessClassifier.classify(TEST_APP_ID, TEST_APP_ID).role,
        )
        assertEquals(
            ProcessRole.ENGINE,
            ProcessClassifier.classify(TEST_APP_ID, "$TEST_APP_ID:engine").role,
        )
        assertEquals(
            ProcessRole.ISOLATED,
            ProcessClassifier.classify(TEST_APP_ID, "$TEST_APP_ID:isolated").role,
        )
        assertEquals(
            ProcessRole.AUXILIARY,
            ProcessClassifier.classify(TEST_APP_ID, "$TEST_APP_ID:worker").role,
        )
    }

    @Test
    fun `accepts short suffixes and default isolated process name`() {
        assertEquals(
            ProcessRole.ENGINE,
            ProcessClassifier.classify(TEST_APP_ID, ":engine").role,
        )
        assertEquals(
            ProcessRole.ISOLATED,
            ProcessClassifier.classify(TEST_APP_ID, ":isolated").role,
        )
        assertEquals(
            ProcessRole.ISOLATED,
            ProcessClassifier.classify(TEST_APP_ID, "isolated").role,
        )
        assertEquals(
            ProcessRole.ISOLATED,
            ProcessClassifier.classify(
                TEST_APP_ID,
                "$TEST_APP_ID:isolated:com.deepstudent.app.process.IsolatedProbeService",
            ).role,
        )
    }

    @Test
    fun `keeps unknown and missing names out of main role`() {
        val unknown = ProcessClassifier.classify(TEST_APP_ID, "$TEST_APP_ID.engine:worker")
        val missing = ProcessClassifier.classify(TEST_APP_ID, "  ")
        val nullName = ProcessClassifier.classify(TEST_APP_ID, null)

        assertEquals(ProcessRole.AUXILIARY, unknown.role)
        assertEquals(ProcessRole.AUXILIARY, missing.role)
        assertEquals(ProcessRole.AUXILIARY, nullName.role)
        assertTrue(unknown.hasKnownName)
        assertFalse(missing.hasKnownName)
        assertFalse(nullName.hasKnownName)
        assertEquals(ProcessNameSource.UNAVAILABLE, missing.source)
    }

    @Test
    fun `preserves supplied name source`() {
        val identity = ProcessClassifier.classify(
            applicationId = TEST_APP_ID,
            processName = "$TEST_APP_ID:engine",
            source = ProcessNameSource.PROC_CMDLINE,
        )

        assertEquals(ProcessNameSource.PROC_CMDLINE, identity.source)
    }
}
