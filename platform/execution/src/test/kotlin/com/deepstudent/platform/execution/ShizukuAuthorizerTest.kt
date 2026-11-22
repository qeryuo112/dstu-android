package com.deepstudent.platform.execution

import com.deepstudent.services.execution.api.ExecutionFailureCode
import com.deepstudent.services.execution.api.ShizukuConnectionState
import com.deepstudent.services.execution.api.ShizukuIdentity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuAuthorizerTest {
    private val source = FakeConnectionSource()
    private val authorizer = ShizukuAuthorizer(
        source = source,
        log = {},
        pollIntervalMillis = 25,
    )

    @After
    fun close() {
        authorizer.close()
    }

    @Test
    fun packageAbsenceAndStoppedServiceAreDifferentStates() = runBlocking {
        source.installedVersion = null
        authorizer.refresh()
        assertEquals(
            ShizukuConnectionState.NOT_INSTALLED,
            authorizer.status.value.state,
        )

        source.installedVersion = "13.5.4"
        authorizer.refresh()
        assertEquals(
            ShizukuConnectionState.NOT_RUNNING,
            authorizer.status.value.state,
        )
    }

    @Test
    fun permissionRequiredReadyRevokedAreRecordedWithoutAutoRequest() = runBlocking {
        val transport = FakeServiceTransport(permissionGranted = false)
        source.binding = binding(transport)
        authorizer.refresh()
        assertEquals(
            ShizukuConnectionState.PERMISSION_REQUIRED,
            authorizer.status.value.state,
        )
        assertEquals(0, source.permissionRequests)
        assertEquals(0, transport.uidReads.get())

        transport.permissionGranted = true
        authorizer.refresh()
        assertEquals(ShizukuConnectionState.READY, authorizer.status.value.state)
        assertEquals(ShizukuIdentity.UNVERIFIED, authorizer.status.value.identity)
        assertEquals(1, transport.uidReads.get())
        assertEquals(0, source.permissionRequests)

        transport.permissionGranted = false
        authorizer.refresh()
        assertEquals(ShizukuConnectionState.REVOKED, authorizer.status.value.state)
        assertEquals(
            ExecutionFailureCode.PERMISSION_REVOKED,
            authorizer.status.value.diagnostic,
        )
    }

    @Test
    fun explicitPermissionRequestRefreshesToReady() = runBlocking {
        val transport = FakeServiceTransport(permissionGranted = false)
        source.binding = binding(transport)
        authorizer.refresh()
        transport.permissionGranted = true
        source.permissionResult = true
        var granted = false
        authorizer.requestPermission { granted = it }
        waitUntil { granted && authorizer.status.value.canExecute }
        assertEquals(1, source.permissionRequests)
        assertEquals(ShizukuConnectionState.READY, authorizer.status.value.state)
    }

    @Test
    fun untrustedBackendUidNeverReachesReady() = runBlocking {
        source.binding = binding(
            FakeServiceTransport(uid = 10123, permissionGranted = true),
            uid = 10123,
        )
        authorizer.refresh()
        assertEquals(
            ShizukuConnectionState.UNTRUSTED_BACKEND,
            authorizer.status.value.state,
        )
        assertEquals(ShizukuIdentity.UNTRUSTED, authorizer.status.value.identity)
    }

    @Test
    fun binderTransactionFailurePublishesDeadState() = runBlocking {
        source.binding = binding(FakeServiceTransport(permissionGranted = true))
        authorizer.refresh()
        assertEquals(ShizukuConnectionState.READY, authorizer.status.value.state)

        source.bindingError = android.os.DeadObjectException()
        authorizer.refresh()
        assertEquals(
            ShizukuConnectionState.BINDER_DEAD,
            authorizer.status.value.state,
        )
        assertEquals(ExecutionFailureCode.BINDER_DEAD, authorizer.status.value.diagnostic)
    }

    @Test
    fun binderDeathClearsVerifiedUidAndRestartRequiresFreshVerification() = runBlocking {
        val transport = FakeServiceTransport(permissionGranted = true)
        val current = binding(transport)
        source.binding = current
        authorizer.refresh()
        authorizer.recordVerifiedUid(current, 2000)
        assertEquals(ShizukuIdentity.SHELL, authorizer.status.value.identity)

        source.binding = null
        authorizer.start()
        waitUntil {
            authorizer.status.value.state == ShizukuConnectionState.NOT_RUNNING
        }
        source.killBinder()
        waitUntil {
            authorizer.status.value.state == ShizukuConnectionState.BINDER_DEAD
        }
        assertEquals(null, authorizer.status.value.observedUid)

        source.binding = binding(FakeServiceTransport(permissionGranted = true), id = "binder-restarted")
        source.receiveBinder()
        waitUntil { authorizer.status.value.state == ShizukuConnectionState.READY }
        assertEquals(null, authorizer.status.value.observedUid)
        assertTrue(source.listeners.isNotEmpty())
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            delay(20)
        }
        error("condition not reached")
    }
}
