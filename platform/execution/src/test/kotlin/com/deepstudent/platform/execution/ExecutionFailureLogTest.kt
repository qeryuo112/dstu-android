package com.deepstudent.platform.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionFailureLogTest {
    @Test
    fun failureLogContainsOnlyFixedStageAndExceptionClassNotMessageCauseOrStack() {
        val records = mutableListOf<ExecutionLogRecord>()
        val sink = ExecutionLogSink { records += it }
        val failure = SecurityException("secret-user-command", IllegalStateException("secret-credential"))
        val result = runCatching {
            sink.atStage(ExecutionStage.PERMISSION_CHECK) { throw failure }
        }
        assertTrue(result.exceptionOrNull() === failure)
        val line = records.single().format()
        assertEquals(
            "owner=platform.execution event=boundary_failure stage=PERMISSION_CHECK exception=java.lang.SecurityException",
            line,
        )
        assertFalse(line.contains("secret"))
        assertFalse(line.contains("IllegalStateException"))
        assertFalse(line.contains('\n'))
    }
}
