package com.deepstudent.platform.execution

import com.deepstudent.services.execution.api.ShizukuConnectionState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuAuthorizationRaceTest {
    @Test
    fun nonDeathBindingFailureIsNotReportedAsADeadBinder() = runBlocking {
        val records = mutableListOf<ExecutionLogRecord>()
        val source = FakeConnectionSource().apply {
            bindingError = SecurityException("private backend details")
        }
        val authorizer = ShizukuAuthorizer(source, log = { records += it })
        try {
            authorizer.refresh()
            assertEquals(ShizukuConnectionState.PROTOCOL_ERROR, authorizer.status.value.state)
            assertEquals(null, authorizer.authorizedBinding())
            val entry = records.filterIsInstance<ExecutionFailureLogEvent>().single()
            assertEquals(ExecutionStage.BINDING_LOOKUP, entry.stage)
            assertEquals(SecurityException::class.java.name, entry.exceptionClass)
        } finally {
            authorizer.close()
        }
    }

    @Test
    fun deniedFreshPermissionOnHandshakeBindingKeepsTheOfficialRequestAvailable() = runBlocking {
        val source = FakeConnectionSource().apply {
            binding = binding(FakeServiceTransport(permissionGranted = false))
        }
        val authorizer = ShizukuAuthorizer(source, log = {})
        try {
            authorizer.refresh()
            assertEquals(ShizukuConnectionState.PERMISSION_REQUIRED, authorizer.status.value.state)
            assertTrue(authorizer.status.value.canRequestPermission)
            authorizer.requestPermission { }
            assertEquals(1, source.permissionRequests)
        } finally {
            authorizer.close()
        }
    }

    @Test
    fun closeDuringRefreshCannotPublishReadyAfterClosed() = runBlocking {
        staleRefresh { it.close() }
    }

    @Test
    fun binderDeathDuringRefreshCannotRepublishTheDeadBinding() = runBlocking {
        staleRefresh { it.markBinderDead() }
    }

    @Test
    fun permissionResultIsDeliveredOnceWhenClosingWithARequestPending() = runBlocking {
        val source = FakeConnectionSource().apply {
            binding = binding(FakeServiceTransport(permissionGranted = false))
            holdPermission = true
        }
        val authorizer = ShizukuAuthorizer(source, log = {})
        try {
            authorizer.refresh()
            val received = mutableListOf<Boolean>()
            authorizer.requestPermission { received += it }
            assertEquals(1, source.permissionRequests)
            authorizer.close()
            assertEquals(listOf(false), received)
            source.heldPermission?.invoke(true)
            assertEquals(listOf(false), received)
        } finally {
            authorizer.close()
        }
    }

    @Test
    fun binderReplacementClearsVerifiedIdentityAndSynchronouslyInvalidatesOwners() = runBlocking {
        val source = FakeConnectionSource()
        val first = binding(FakeServiceTransport())
        source.binding = first
        val authorizer = ShizukuAuthorizer(source, log = {})
        try {
            authorizer.refresh()
            authorizer.recordVerifiedUid(first, 2000)
            var invalidations = 0
            authorizer.onInvalidated = { invalidations++ }
            source.binding = binding(FakeServiceTransport(), id = "replacement-same-uid")
            authorizer.refresh()
            assertEquals(ShizukuConnectionState.READY, authorizer.status.value.state)
            assertNull(authorizer.status.value.observedUid)
            assertEquals(1, invalidations)
            authorizer.recordVerifiedUid(first, 2000)
            assertNull(authorizer.status.value.observedUid)
        } finally {
            authorizer.close()
        }
    }

    private suspend fun staleRefresh(terminate: (ShizukuAuthorizer) -> Unit) = kotlinx.coroutines.coroutineScope {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val source = FakeConnectionSource().apply {
            binding = binding(FakeServiceTransport().apply {
                permissionCheck = {
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                    true
                }
            })
        }
        val authorizer = ShizukuAuthorizer(source, log = {})
        val refreshing = async { authorizer.refresh() }
        try {
            withTimeout(2_000) { while (entered.count != 0L) delay(5) }
            terminate(authorizer)
            val terminal = authorizer.status.value.state
            release.countDown()
            refreshing.await()
            assertTrue(terminal == ShizukuConnectionState.CLOSED || terminal == ShizukuConnectionState.BINDER_DEAD)
            assertEquals(terminal, authorizer.status.value.state)
            assertNull(authorizer.authorizedBinding())
        } finally {
            release.countDown()
            authorizer.close()
        }
    }
}
