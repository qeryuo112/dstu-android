package com.deepstudent.app.sync

import com.deepstudent.app.graph.ProcessRole
import com.deepstudent.features.sync.ui.SyncController
import com.deepstudent.services.sync.api.PairingMode
import com.deepstudent.services.sync.api.PairingRequest
import com.deepstudent.services.sync.api.SyncConfiguration
import com.deepstudent.services.sync.api.SyncConfigurationService
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.api.SyncResult
import com.deepstudent.services.sync.api.SyncTransfer
import com.deepstudent.services.sync.api.SyncTransferPhase
import com.deepstudent.services.sync.api.SyncTransferState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LazySyncOwnerTest {
    private class Resource : AutoCloseable {
        var closes = 0
        override fun close() { closes++ }
    }

    @Test fun mainOwnerOpensOnceAndClosesOnce() = runTest {
        val opens = AtomicInteger()
        val owner = LazySyncOwner({ ProcessRole.MAIN }) { opens.incrementAndGet(); Resource() }
        assertFalse(owner.isInitialized)
        val values = List(8) { async { owner.get() } }.awaitAll()
        values.forEach { assertSame(values.first(), it) }
        assertEquals(1, opens.get())
        assertTrue(owner.isInitialized)
        owner.close()
        owner.close()
        assertEquals(1, values.first().closes)
        assertFalse(owner.isInitialized)
    }

    @Test fun closingMainResourcesCancelsTransferAndPreventsReopen() = runTest {
        val config = SyncConfiguration("endpoint", "user", "root", "device", "instance")
        val service = object : SyncConfigurationService {
            override suspend fun updateEncryptionPassword(password: CharArray) = error("not used")
            override suspend fun current(): SyncResult<SyncConfiguration?> = SyncResult.Success(config)
            override suspend fun pair(mode: PairingMode, request: PairingRequest): SyncResult<SyncConfiguration> =
                SyncResult.Failure(SyncError.AUTHENTICATION)
            override suspend fun clear(): SyncResult<Unit> = SyncResult.Success(Unit)
        }
        val transfer = object : SyncTransfer {
            override val state = MutableStateFlow(SyncTransferState(SyncTransferPhase.IDLE))
            override suspend fun synchronize(): SyncResult<Unit> {
                state.value = SyncTransferState(SyncTransferPhase.DOWNLOAD)
                awaitCancellation()
            }
        }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val controller = SyncController(service, scope, { transfer })
        val owner = LazySyncOwner({ ProcessRole.MAIN }) { MainSyncResources(controller, scope) }
        owner.get()
        runCurrent()
        controller.synchronize()
        runCurrent()
        assertTrue(controller.running.value)
        owner.close()
        runCurrent()
        assertFalse(controller.running.value)
        assertEquals(SyncError.CANCELLED, controller.transfer.value.error)
        assertTrue(runCatching { owner.get() }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun nonMainRolesNeverOpenResources() = runTest {
        for (role in listOf(ProcessRole.ENGINE, ProcessRole.ISOLATED)) {
            var opens = 0
            val owner = LazySyncOwner({ role }) { opens++; Resource() }
            val failure = runCatching { owner.get() }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals(0, opens)
            assertFalse(owner.isInitialized)
            owner.close()
        }
    }
}
