package com.deepstudent.features.workspace.backend

import com.deepstudent.features.workspace.api.*
import com.deepstudent.features.workspace.backend.application.HostRegistration
import com.deepstudent.features.workspace.backend.domain.LayoutSnapshotCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceSessionTest {
    @Test fun repeatedResourceFocusesWithoutMovingOrDuplicating() = runTest {
        val fixture = WorkspaceFixture()
        val (session, panel) = fixture.opened()
        val second = session.open(OpenPanel(panel.key, ResourceKey("note", "two")))
        assertEquals(panel.id, session.open(OpenPanel(panel.key, panel.resource)).id)
        assertEquals(listOf(panel.id, second.id), session.state.value.layout.panels.map { it.id })
        assertEquals(panel.id, session.state.value.layout.activePanelId)
        assertTrue(fixture.registry.register(fixture.host) is HostRegistration.Conflict)
    }

    @Test fun dirtyCancelPreservesBytesLayoutAndFocus() = runTest {
        val (session, panel) = WorkspaceFixture().opened()
        session.updateDraft(panel.id, "未保存👩‍💻".toByteArray())
        val before = session.state.value.layout
        assertEquals(CloseResult.NeedsDecision, session.close(panel.id))
        assertEquals(CloseResult.Cancelled, session.resolveClose(CloseDecision.Cancel))
        assertEquals(before, session.state.value.layout)
        assertEquals("未保存👩‍💻", session.loadDraft(panel.id)!!.bytes.toString(Charsets.UTF_8))
    }

    @Test fun draftAndFocusRecoverFromFreshSessionAndSnapshotRoundTrip() = runTest {
        val fixture = WorkspaceFixture()
        val (session, panel) = fixture.opened()
        session.updateDraft(panel.id, byteArrayOf(1, 2))
        session.setMode(WorkspaceMode.TabletPanels)
        val recovered = fixture.session()
        recovered.restore()
        assertTrue(recovered.state.value.layout.panel(panel.id)!!.dirty)
        assertEquals(panel.id, recovered.state.value.layout.activePanelId)
        val codec = LayoutSnapshotCodec()
        assertEquals(recovered.state.value.layout, codec.decode(codec.encode(recovered.state.value.layout)))
        assertThrows(Exception::class.java) { codec.decode("{broken") }
    }

    @Test fun corruptDraftFailsRestoreWithoutPublishingCleanState() = runTest {
        val fixture = WorkspaceFixture()
        val (session, panel) = fixture.opened()
        session.updateDraft(panel.id, byteArrayOf(1))
        fixture.store.failLoad = true
        val restored = fixture.session()
        restored.restore()
        assertEquals(WorkspaceStatus.Error, restored.state.value.status)
        assertTrue(fixture.store.layout!!.panel(panel.id)!!.dirty)
        assertNotNull(fixture.store.drafts[panel.id])
    }

    @Test fun newerInputDuringSaveIsNeverDeletedOrClosed() = runTest {
        val fixture = WorkspaceFixture()
        val (session, panel) = fixture.opened()
        session.updateDraft(panel.id, byteArrayOf(1))
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        fixture.host.saveAction = { entered.complete(Unit); finish.await(); PanelSaveResult.Saved() }
        session.close(panel.id)
        val closing = async { session.resolveClose(CloseDecision.Save) }
        entered.await()
        session.updateDraft(panel.id, byteArrayOf(2))
        assertEquals(panel.id, session.state.value.savingPanelId)
        finish.complete(Unit)
        assertEquals(CloseResult.NeedsDecision, closing.await())
        assertArrayEquals(byteArrayOf(2), session.loadDraft(panel.id)!!.bytes)
        assertTrue(session.state.value.layout.panel(panel.id)!!.dirty)
    }

    @Test fun cancellationAfterDispatchStillFinishesReceiptAndClearsBusy() = runTest {
        val fixture = WorkspaceFixture()
        val (session, panel) = fixture.opened()
        session.updateDraft(panel.id, byteArrayOf(1))
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        fixture.host.saveAction = { entered.complete(Unit); finish.await(); PanelSaveResult.Saved() }
        val job = launch { session.save(panel.id) }
        entered.await()
        job.cancel()
        finish.complete(Unit)
        job.join()
        assertNull(session.state.value.savingPanelId)
        assertFalse(session.state.value.layout.panel(panel.id)!!.dirty)
        assertEquals(1, fixture.host.saveCalls)
    }

    @Test fun uncertainSaveBlocksRetryAcrossRestartUntilAcknowledged() = runTest {
        val fixture = WorkspaceFixture()
        val (session, panel) = fixture.opened()
        session.updateDraft(panel.id, byteArrayOf(1))
        fixture.host.saveAction = { error("commit response missing") }
        assertTrue(session.save(panel.id) is SaveOutcome.Uncertain)
        assertTrue(session.save(panel.id) is SaveOutcome.Uncertain)
        val restored = fixture.session()
        restored.restore()
        assertTrue(restored.save(panel.id) is SaveOutcome.Uncertain)
        assertEquals(1, fixture.host.saveCalls)
        restored.acknowledgeSaveUncertain(panel.id)
        fixture.host.saveAction = { PanelSaveResult.Saved() }
        assertEquals(SaveOutcome.Saved, restored.save(panel.id))
    }

    @Test fun closeLayoutFailureKeepsPanelAndDraft() = runTest {
        val fixture = WorkspaceFixture()
        val (session, panel) = fixture.opened()
        session.updateDraft(panel.id, byteArrayOf(1))
        session.close(panel.id)
        fixture.store.failLayout = true
        assertEquals(CloseResult.Cancelled, session.resolveClose(CloseDecision.Discard))
        assertNotNull(session.state.value.layout.panel(panel.id))
        assertNotNull(fixture.store.drafts[panel.id])
    }

    @Test fun failedDiscardAndConcurrentInputKeepDraft() = runTest {
        val fixture = WorkspaceFixture()
        val (session, panel) = fixture.opened()
        session.updateDraft(panel.id, byteArrayOf(1))
        session.close(panel.id)
        fixture.host.discardAction = { error("domain rejected discard") }
        assertTrue(session.resolveClose(CloseDecision.Discard) is CloseResult.DiscardFailed)
        assertNotNull(session.loadDraft(panel.id))
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        fixture.host.discardAction = { entered.complete(Unit); finish.await() }
        val closing = async { session.resolveClose(CloseDecision.Discard) }
        entered.await()
        session.updateDraft(panel.id, byteArrayOf(2))
        finish.complete(Unit)
        assertEquals(CloseResult.NeedsDecision, closing.await())
        assertArrayEquals(byteArrayOf(2), session.loadDraft(panel.id)!!.bytes)
    }
}
