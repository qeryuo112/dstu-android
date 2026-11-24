package com.deepstudent.platform.execution

import com.deepstudent.services.execution.api.ExecutionFailureCode
import com.deepstudent.services.execution.api.ExecutionOutput
import com.deepstudent.services.execution.api.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellProtocolTest {
    @Test fun approvedPayloadIsNotInterpolatedIntoAndroidBootstrap() {
        val command = "printf '%s\\n' 'a b'; exit 7"
        val argv = ShellProtocol.androidArgv(command, 2000, "/data/local/tmp", owned = true)
        assertEquals("/system/bin/setsid", argv.first())
        assertEquals(command, argv.last())
        val wrapper = argv[3]
        assertFalse(wrapper.contains(command))
        assertTrue(wrapper.indexOf("/system/bin/id -u") < wrapper.indexOf("/system/bin/env"))
        assertTrue(wrapper.contains("</dev/null 3<&-"))
        assertTrue(wrapper.contains("kill -TERM"))
    }

    @Test fun receiptsRequireInitialIdentityAndStripOnlyTheControlLine() {
        val output = ExecutionOutput("DSTU_SHELL_UID:2000\r\nhello\n", "err", true, false)
        val result = ShellProtocol.result(2000, 7, output, System.nanoTime()) as ShellResult.Exited
        assertEquals(7, result.receipt.exitCode)
        assertEquals(2000, result.receipt.observedUid)
        assertEquals("hello\n", result.receipt.output.stdout)
        assertEquals("err", result.receipt.output.stderr)
        assertTrue(result.receipt.output.stdoutTruncated)
        assertEquals(
            ShellResult.Unavailable(ExecutionFailureCode.UID_MISMATCH),
            ShellProtocol.result(0, 7, output, System.nanoTime()),
        )
        assertEquals(null, ShellProtocol.observedUid("noise\nDSTU_SHELL_UID:2000"))
    }
}
