package com.deepstudent.services.execution.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionContractTest {
    @Test
    fun identityNeverClaimsRootWithoutMatchingObservedUid() {
        assertEquals(
            ShizukuIdentity.UNVERIFIED,
            ShizukuStatus(state = ShizukuConnectionState.READY, backendUid = 0).identity,
        )
        assertEquals(
            ShizukuIdentity.MISMATCH,
            ShizukuStatus(
                state = ShizukuConnectionState.READY,
                backendUid = 2000,
                observedUid = 0,
            ).identity,
        )
        assertEquals(
            ShizukuIdentity.ROOT,
            ShizukuStatus(
                state = ShizukuConnectionState.READY,
                backendUid = 0,
                observedUid = 0,
            ).identity,
        )
        assertEquals(
            ShizukuIdentity.UNTRUSTED,
            ShizukuStatus(
                state = ShizukuConnectionState.UNTRUSTED_BACKEND,
                backendUid = 10123,
            ).identity,
        )
    }

    @Test
    fun commandsHaveFixedArgvAndNoShell() {
        assertEquals(listOf("id"), ExecutionCommand.Identity.argv)
        assertEquals(
            listOf("pm", "list", "packages"),
            ExecutionCommand.PackageList.argv,
        )
        assertFalse(ExecutionCommand.Identity.argv.any { it == "sh" })
        assertFalse(ExecutionCommand.PackageList.argv.any { it == "-c" })
        assertFalse(ExecutionCommand.PackageList.argv.any { it.startsWith("-") })
        assertEquals("pm.list.packages", ExecutionCommand.PackageList.commandId)
    }

    @Test
    fun argvMutationCannotChangeFutureRequests() {
        val snapshot = ExecutionCommand.PackageList.argv as MutableList<String>
        snapshot[0] = "sh"
        assertEquals(listOf("pm", "list", "packages"), ExecutionCommand.PackageList.argv)
    }

    @Test
    fun readyAndPermissionStatesOwnTheirActions() {
        assertTrue(
            ShizukuStatus(state = ShizukuConnectionState.READY).canExecute,
        )
        assertTrue(
            ShizukuStatus(state = ShizukuConnectionState.PERMISSION_REQUIRED)
                .canRequestPermission,
        )
        assertFalse(
            ShizukuStatus(state = ShizukuConnectionState.REVOKED).canExecute,
        )
        assertTrue(ExecutionFailureCode.CANCELLED in ExecutionFailureCode.entries)
    }
}
