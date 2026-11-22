package com.deepstudent.features.environments.ui

import com.deepstudent.features.environments.api.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EnvironmentsPresenterTest {
    @Test fun initialBusyStateAndLatestTaskAreObservedWithoutAnyIo() = runTest {
        val manager = FakeEnvironmentManager(EnvironmentSnapshot(phase = EnvironmentPhase.EXTRACTING))
        val presenter = EnvironmentsPresenter(manager, this) { error("must not pick") }
        try {
            assertTrue(presenter.state.value.busy)
            presenter.actions.installRootfs()
            presenter.actions.downloadRootfs()
            runCurrent()
            assertEquals(0, manager.rootfsCalls)
            manager.state.value = EnvironmentSnapshot(phase = EnvironmentPhase.READY, activeGeneration = "generation")
            runCurrent()
            assertFalse(presenter.state.value.busy)
        } finally { presenter.stop() }
    }

    @Test fun cancelledPickerResetsPendingAndRapidDoubleClickLaunchesOnlyOnePicker() = runTest {
        val manager = FakeEnvironmentManager()
        val picker = CompletableDeferred<RootfsArchiveSource?>()
        var calls = 0
        val presenter = EnvironmentsPresenter(manager, this) { calls++; picker.await() }
        try {
            presenter.actions.installRootfs()
            presenter.actions.installRootfs()
            runCurrent()
            assertEquals(1, calls)
            assertTrue(presenter.state.value.busy)
            picker.complete(null)
            runCurrent()
            assertFalse(presenter.state.value.busy)
            assertEquals(0, manager.rootfsCalls)
            assertNull(presenter.state.value.failure)
        } finally { presenter.stop() }
    }

    @Test fun presenterPassesOpaqueSourceAndNeverOpensIt() = runTest {
        val manager = FakeEnvironmentManager()
        val source = RootfsArchiveSource { error("UI must not open a stream") }
        val presenter = EnvironmentsPresenter(manager, this) { source }
        try {
            presenter.actions.installRootfs()
            runCurrent()
            assertSame(source, manager.source)
            assertEquals(1, manager.rootfsCalls)
            presenter.actions.retry()
            runCurrent()
            assertSame(source, manager.source)
            assertEquals(1, manager.retryCalls)
        } finally { presenter.stop() }
    }

    @Test fun detachDoesNotCancelAcceptedTaskAndNewObserverSeesIt() = runTest {
        val manager = FakeEnvironmentManager()
        val first = EnvironmentsPresenter(manager, this) { null }
        manager.state.value = EnvironmentSnapshot(phase = EnvironmentPhase.INSTALLING_PACKAGE,
            runtime = EnvironmentRuntimeState(taskId = EnvironmentTaskId("task-running")))
        runCurrent()
        first.stop()
        assertEquals(0, manager.cancelCalls)
        val second = EnvironmentsPresenter(manager, this) { null }
        try {
            assertTrue(second.state.value.busy)
            second.actions.cancel()
            runCurrent()
            assertEquals(1, manager.cancelCalls)
            assertEquals("task-running", manager.cancelledTask?.value)
        } finally { second.stop() }
    }

    @Test fun unknownActionFailureIsSanitizedAndNewCommandClearsIt() = runTest {
        val manager = FakeEnvironmentManager()
        var failPicker = true
        val presenter = EnvironmentsPresenter(manager, this) {
            if (failPicker) error("content://private?token=secret") else null
        }
        try {
            presenter.actions.installRootfs()
            runCurrent()
            assertEquals(EnvironmentFailureCode.UNKNOWN, presenter.state.value.failure?.code)
            assertFalse(presenter.state.value.failure!!.message.contains("secret"))
            failPicker = false
            presenter.actions.installRootfs()
            runCurrent()
            assertNull(presenter.state.value.failure)
        } finally { presenter.stop() }
    }

    @Test fun downloadAndImportAreIndependentAndRetryNeverSwitchesSources() = runTest {
        val manager = FakeEnvironmentManager()
        var picks = 0
        val downloaded = RootfsArchiveSource { error("UI must not open the network") }
        val imported = RootfsArchiveSource { error("UI must not open the document") }
        val presenter = EnvironmentsPresenter(manager, this, downloaded) { picks++; imported }
        try {
            assertTrue(presenter.state.value.downloadAvailable)
            presenter.actions.downloadRootfs()
            runCurrent()
            assertSame(downloaded, manager.source)
            assertEquals(1, manager.rootfsCalls)
            assertEquals(0, picks)
            manager.state.value = EnvironmentSnapshot(phase = EnvironmentPhase.FAILED,
                runtime = EnvironmentRuntimeState(failure = EnvironmentFailure(EnvironmentFailureCode.ARCHIVE_MISSING, "missing", true), recoverable = true))
            runCurrent()
            presenter.actions.retry()
            runCurrent()
            assertSame(downloaded, manager.source)
            assertEquals(1, manager.retryCalls)
            assertEquals(0, picks)
            presenter.actions.installRootfs()
            runCurrent()
            assertSame(imported, manager.source)
            assertEquals(1, picks)
            assertEquals(2, manager.rootfsCalls)
            presenter.actions.retry()
            runCurrent()
            assertSame(imported, manager.source)
            assertEquals(2, manager.retryCalls)
            assertEquals(1, picks)
        } finally { presenter.stop() }
    }

    @Test fun recreatedPresenterContinuesWithoutImplicitPickerOrNetworkSource() = runTest {
        val manager = FakeEnvironmentManager(EnvironmentSnapshot(phase = EnvironmentPhase.FAILED,
            runtime = EnvironmentRuntimeState(failure = EnvironmentFailure(EnvironmentFailureCode.ARCHIVE_MISSING, "missing", true), recoverable = true)))
        var picks = 0
        val downloaded = RootfsArchiveSource { error("not UI I/O") }
        val presenter = EnvironmentsPresenter(manager, this, downloaded) { picks++; error("not an implicit picker") }
        try {
            presenter.actions.retry()
            runCurrent()
            assertEquals(0, picks)
            assertEquals(1, manager.retryCalls)
            assertNull(manager.source)
            presenter.actions.downloadRootfs()
            runCurrent()
            assertSame(downloaded, manager.source)
        } finally { presenter.stop() }
    }
}

private class FakeEnvironmentManager(initial: EnvironmentSnapshot = EnvironmentSnapshot()) : EnvironmentManager {
    override val state = MutableStateFlow(initial)
    var rootfsCalls = 0
    var retryCalls = 0
    var cancelCalls = 0
    var cancelledTask: EnvironmentTaskId? = null
    var source: RootfsArchiveSource? = null
    override suspend fun installRootfs(manifest: RootfsManifest, source: RootfsArchiveSource): EnvironmentTaskId {
        rootfsCalls++; this.source = source
        return EnvironmentTaskId("task-ui-import")
    }
    override suspend fun installPackage(recipeId: String) = EnvironmentTaskId("task-ui-package")
    override suspend fun retry(source: RootfsArchiveSource?): EnvironmentTaskId {
        retryCalls++; this.source = source
        return EnvironmentTaskId("task-ui-retry")
    }
    override suspend fun cancel(taskId: EnvironmentTaskId) { cancelCalls++; cancelledTask = taskId }
}
