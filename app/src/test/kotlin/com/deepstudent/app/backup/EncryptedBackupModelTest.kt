package com.deepstudent.app.backup

import com.deepstudent.services.backup.api.CoverageStatus
import com.deepstudent.services.backup.api.DomainAction
import com.deepstudent.services.backup.api.DomainPreview
import com.deepstudent.services.backup.api.RestorePlan
import com.deepstudent.services.backup.api.RestorePlanSource
import com.deepstudent.services.backup.api.SnapshotKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EncryptedBackupModelTest {
    @Test fun emptyOrCancelledPasswordNeverReachesThePort() = runTest {
        val port = FakePort()
        val model = EncryptedBackupWorkflow(port, this)
        model.select("source", EncryptedBackupIntent.IMPORT)
        model.authenticate(charArrayOf())
        assertEquals(EncryptedBackupState.Password(EncryptedBackupIntent.IMPORT), model.state.value)
        model.cancel()
        val discarded = "secret".toCharArray()
        model.authenticate(discarded)
        runCurrent()
        assertEquals(EncryptedBackupState.Idle, model.state.value)
        assertTrue(port.prepares.isEmpty())
        assertCleared(discarded)
    }

    @Test fun failedAuthenticationRetriesSameSelectionWithoutKeepingPassword() = runTest {
        val port = FakePort().apply { failure = EncryptedBackupFileException(EncryptedBackupFileError.AUTHENTICATION_FAILED) }
        val model = EncryptedBackupWorkflow(port, this)
        model.select("source-a", EncryptedBackupIntent.IMPORT)
        val wrong = "wrong".toCharArray()
        model.authenticate(wrong)
        runCurrent()
        assertCleared(wrong)
        val error = model.state.value as EncryptedBackupState.Failed
        assertTrue(error.message.contains("密码不正确"))
        assertTrue(error.canRetry)
        assertEquals(0, port.schedules)
        port.failure = null
        model.retry()
        assertEquals(EncryptedBackupState.Password(EncryptedBackupIntent.IMPORT), model.state.value)
        val right = "right".toCharArray()
        model.authenticate(right)
        runCurrent()
        assertCleared(right)
        assertEquals(listOf("source-a", "source-a"), port.prepares.map { it.first })
        assertEquals(EncryptedBackupState.Report(port.item.plan!!), model.state.value)
        assertEquals(0, port.schedules)
        model.close()
    }

    @Test fun restoreRequiresReportAndStageablePlanAndRejectCanBeRetried() = runTest {
        val port = FakePort().apply { item = Item(plan = fixturePlan(canStage = false)) }
        val model = EncryptedBackupWorkflow(port, this)
        model.confirmRestore()
        model.select("source", EncryptedBackupIntent.IMPORT)
        model.authenticate("pw".toCharArray())
        runCurrent()
        model.confirmRestore()
        assertEquals(0, port.schedules)
        model.select("source2", EncryptedBackupIntent.IMPORT)
        assertEquals(1, port.released.size)
        port.item = Item(plan = fixturePlan(canStage = true))
        port.allowSchedule = false
        model.authenticate("pw".toCharArray())
        runCurrent()
        model.confirmRestore()
        runCurrent()
        assertEquals(1, port.schedules)
        assertTrue(model.state.value is EncryptedBackupState.Report)
        assertEquals(1, port.released.size)
        port.allowSchedule = true
        model.confirmRestore()
        runCurrent()
        assertEquals(2, port.schedules)
        assertTrue(model.state.value is EncryptedBackupState.Done)
        assertEquals(2, port.released.size)
        model.confirmRestore()
        assertEquals(2, port.schedules)
    }

    @Test fun exportNeedsSaveDestinationAndReleasesAfterSuccess() = runTest {
        val port = FakePort().apply { item = Item(plan = null, zip = true) }
        val model = EncryptedBackupWorkflow(port, this)
        model.save("too-early")
        model.select("source", EncryptedBackupIntent.EXPORT_PORTABLE)
        model.authenticate("pw".toCharArray())
        runCurrent()
        assertEquals(EncryptedBackupState.ReadyToSave("deepstudent-encrypted.zip", "application/zip"), model.state.value)
        assertTrue(port.exports.isEmpty())
        model.save("destination")
        runCurrent()
        assertEquals(listOf("destination"), port.exports)
        assertTrue(model.state.value is EncryptedBackupState.Done)
        assertEquals(listOf(port.item), port.released)
    }

    @Test fun failedSaveDoesNotOfferPasswordRetryOrLeakErrorDetails() = runTest {
        val port = FakePort().apply {
            item = Item(plan = null)
            exportFailure = IllegalStateException("private destination details")
        }
        val model = EncryptedBackupWorkflow(port, this)
        model.select("source", EncryptedBackupIntent.EXPORT)
        model.authenticate("pw".toCharArray())
        runCurrent()
        assertEquals(EncryptedBackupState.ReadyToSave("deepstudent-encrypted.dsbk", "application/octet-stream"), model.state.value)
        model.save("destination")
        runCurrent()
        val error = model.state.value as EncryptedBackupState.Failed
        assertFalse(error.canRetry)
        assertFalse(error.message.contains("private"))
        assertEquals(listOf(port.item), port.released)
        model.retry()
        assertSame(error, model.state.value)
    }

    @Test fun cancellingBeforeCoroutineStartsClearsPasswordAndRestoresIdle() = runTest {
        val port = FakePort()
        val model = EncryptedBackupWorkflow(port, this)
        model.select("source", EncryptedBackupIntent.IMPORT)
        val password = "secret".toCharArray()
        model.authenticate(password)
        assertEquals(EncryptedBackupState.Working, model.state.value)
        model.cancel()
        runCurrent()
        assertEquals(EncryptedBackupState.Idle, model.state.value)
        assertCleared(password)
        assertTrue(port.prepares.isEmpty())
    }

    @Test fun cancellingSuspendedWorkBlocksNewSelectionAndClearsPassword() = runTest {
        val port = FakePort().apply { gate = CompletableDeferred() }
        val model = EncryptedBackupWorkflow(port, this)
        model.select("first", EncryptedBackupIntent.IMPORT)
        val password = "secret".toCharArray()
        model.authenticate(password)
        runCurrent()
        model.select("ignored", EncryptedBackupIntent.EXPORT)
        model.cancel()
        runCurrent()
        assertEquals(EncryptedBackupState.Idle, model.state.value)
        assertEquals(listOf("first"), port.prepares.map { it.first })
        assertCleared(password)
        assertEquals(0, port.schedules)
    }

    @Test fun lateNonCooperativeResultIsReleasedAfterCancellation() = runTest {
        val gate = CompletableDeferred<Unit>()
        val port = FakePort().apply { this.gate = gate; ignoreCancellation = true }
        val model = EncryptedBackupWorkflow(port, this)
        model.select("source", EncryptedBackupIntent.IMPORT)
        val password = "secret".toCharArray()
        model.authenticate(password)
        runCurrent()
        model.cancel()
        assertEquals(EncryptedBackupState.Working, model.state.value)
        gate.complete(Unit)
        runCurrent()
        assertEquals(EncryptedBackupState.Idle, model.state.value)
        assertEquals(listOf(port.item), port.released)
        assertCleared(password)
        assertEquals(0, port.schedules)
    }

    private data class Item(val plan: RestorePlan?, val zip: Boolean = false)

    private class FakePort : EncryptedBackupWorkflowPort<String, Item> {
        var item = Item(plan = fixturePlan(canStage = true))
        var failure: Exception? = null
        var exportFailure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        var ignoreCancellation = false
        var allowSchedule = true
        val prepares = mutableListOf<Pair<String, EncryptedBackupIntent>>()
        val exports = mutableListOf<String>()
        val released = mutableListOf<Item>()
        var schedules = 0

        override suspend fun prepare(source: String, intent: EncryptedBackupIntent, password: CharArray): Item {
            prepares += source to intent
            gate?.let { if (ignoreCancellation) withContext(NonCancellable) { it.await() } else it.await() }
            failure?.let { throw it }
            return item
        }
        override fun plan(item: Item) = item.plan
        override fun saveState(item: Item) = if (item.zip) {
            EncryptedBackupState.ReadyToSave("deepstudent-encrypted.zip", "application/zip")
        } else EncryptedBackupState.ReadyToSave("deepstudent-encrypted.dsbk", "application/octet-stream")
        override suspend fun schedule(item: Item): Boolean { schedules++; return allowSchedule }
        override suspend fun export(item: Item, destination: String) { exports += destination; exportFailure?.let { throw it } }
        override fun release(item: Item) { released += item }
    }

    private companion object {
        fun assertCleared(value: CharArray) = assertArrayEquals(CharArray(value.size), value)
        fun fixturePlan(canStage: Boolean) = RestorePlan(SnapshotKind.FULL, RestorePlanSource(),
            listOf(DomainPreview("database:vfs", CoverageStatus.COMPLETE, DomainAction.STAGE_IN_SLOT, 1, 2)),
            emptyList(), 2, emptyList(), canStage)
    }
}
