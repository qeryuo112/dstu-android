package com.deepstudent.services.resources.data

import com.deepstudent.core.common.error.DstuException
import java.nio.file.AccessDeniedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class FileDiagnosticsTest {
    @Test fun onlyFixedStageAndExceptionClassCrossBoundary() {
        val failure = assertThrows(DstuException::class.java) {
            fileOperation(FileStage.PUBLISH) { throw AccessDeniedException("secret-path", null, "secret-message") }
        }
        assertEquals(mapOf("stage" to "PUBLISH", "causeClass" to "AccessDeniedException"), failure.error.details)
        assertFalse(failure.toString().contains("secret"))
        assertFalse(failure.error.toString().contains("secret"))
    }
}
