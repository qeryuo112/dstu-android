package com.deepstudent.features.notes.ui

import com.deepstudent.features.notes.api.CreateNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: FakeNotes
    private lateinit var model: NotesViewModel

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        api = FakeNotes()
        model = NotesViewModel(api, dispatcher)
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun syncRefreshPreservesDirtyDraftAndRefreshesCleanDocument() = runTest(dispatcher) {
        val note = api.create(CreateNote("标题", "原文"))
        advanceUntilIdle()
        model.request(NotesDestination.Open(note.id))
        advanceUntilIdle()
        api.externalEdit(note.id, "远端")
        model.refreshAfterSync()
        advanceUntilIdle()
        assertEquals("远端", model.state.value.draft!!.content)
        assertEquals("远端", model.state.value.notes.single().content)
        model.changeContent("本地")
        api.externalEdit(note.id, "远端再次修改")
        model.refreshAfterSync()
        advanceUntilIdle()
        assertEquals("本地", model.state.value.draft!!.content)
        assertTrue(model.state.value.conflict)
        assertEquals("远端再次修改", model.state.value.notes.single().content)
    }

    @Test fun createEditDeleteRestoreAndBack() = runTest(dispatcher) {
        advanceUntilIdle()
        assertEquals(NotesListStatus.Ready, model.state.value.listStatus)
        model.request(NotesDestination.Create)
        model.changeTitle("中文 📝")
        model.changeContent("正文 👩‍👩‍👧‍👦\n第二行")
        model.save()
        advanceUntilIdle()
        val saved = model.state.value.draft!!.original!!
        assertEquals("中文 📝", saved.title)
        assertEquals("正文 👩‍👩‍👧‍👦\n第二行", saved.content)
        model.changeContent("更新")
        model.save()
        advanceUntilIdle()
        assertEquals(saved.updatedAt, api.lastEdit!!.expectedUpdatedAt)
        model.askDelete()
        model.confirmDelete()
        advanceUntilIdle()
        assertTrue(model.state.value.draft!!.isDeleted)
        assertEquals(NotesCollection.Trash, model.state.value.collection)
        model.restore()
        advanceUntilIdle()
        assertFalse(model.state.value.draft!!.isDeleted)
        assertTrue(model.onBackRequested())
        assertNull(model.state.value.draft)
        assertFalse(model.onBackRequested())
        model.request(NotesDestination.Open(saved.id))
        advanceUntilIdle()
        assertEquals("更新", model.state.value.draft!!.content)
    }

    @Test fun dirtyNavigationRequiresConfirmationAndPreservesTags() = runTest(dispatcher) {
        val first = api.create(CreateNote("一", "内容", listOf("标签")))
        val second = api.create(CreateNote("二", "正文"))
        advanceUntilIdle()
        model.request(NotesDestination.Open(first.id))
        advanceUntilIdle()
        model.changeContent("未保存")
        model.request(NotesDestination.Open(second.id))
        assertTrue(model.state.value.dialog is NotesDialog.Discard)
        model.dismissDialog()
        assertEquals("未保存", model.state.value.draft!!.content)
        model.save()
        advanceUntilIdle()
        assertEquals(listOf("标签"), api.lastEdit!!.tags)
        model.changeContent("再次修改")
        model.onBackRequested()
        model.discardAndContinue()
        assertNull(model.state.value.draft)
    }

    @Test fun conflictRetainsDraftAndExplicitReloadRequiresConfirmation() = runTest(dispatcher) {
        val saved = api.create(CreateNote("标题", "原文"))
        advanceUntilIdle()
        model.request(NotesDestination.Open(saved.id))
        advanceUntilIdle()
        model.changeContent("本地")
        api.externalEdit(saved.id, "远端")
        model.save()
        advanceUntilIdle()
        assertTrue(model.state.value.conflict)
        assertEquals("本地", model.state.value.draft!!.content)
        model.save()
        assertEquals("远端", api.records.getValue(saved.id).content)
        model.askReloadConflict()
        model.reloadConflict()
        advanceUntilIdle()
        assertEquals("远端", model.state.value.draft!!.content)
        assertFalse(model.state.value.conflict)
    }

    @Test fun loadingFailureAndCancellationRemainRetryable() = runTest(dispatcher) {
        val wait = CompletableDeferred<Unit>()
        api.beforeList = { wait.await() }
        runCurrent()
        assertEquals(NotesListStatus.Loading, model.state.value.listStatus)
        model.cancelRead()
        assertEquals(NotesListStatus.Cancelled, model.state.value.listStatus)
        api.beforeList = { throw IllegalStateException("private content") }
        model.refresh()
        advanceUntilIdle()
        assertEquals(NotesListStatus.Error, model.state.value.listStatus)
        api.beforeList = {}
        model.refresh()
        advanceUntilIdle()
        assertEquals(NotesListStatus.Ready, model.state.value.listStatus)
        api.beforeGet = { throw CancellationException("cancel") }
        model.request(NotesDestination.Open("missing"))
        advanceUntilIdle()
        assertEquals("读取已取消", model.state.value.message)
        assertTrue(model.onBackRequested())
    }

    @Test fun writeCancellationDoesNotRetryAnUncertainCreate() = runTest(dispatcher) {
        advanceUntilIdle()
        model.request(NotesDestination.Create)
        api.beforeWrite = { throw CancellationException("cancel") }
        model.save()
        advanceUntilIdle()
        assertTrue(model.state.value.writeUncertain)
        api.beforeWrite = {}
        model.save()
        advanceUntilIdle()
        assertTrue(api.records.isEmpty())
    }

    @Test fun backDuringConflictReloadCancelsReadAndKeepsDraft() = runTest(dispatcher) {
        val saved = api.create(CreateNote("标题", "原文"))
        advanceUntilIdle()
        model.request(NotesDestination.Open(saved.id))
        advanceUntilIdle()
        model.changeContent("本地")
        api.externalEdit(saved.id, "远端")
        model.save()
        advanceUntilIdle()
        api.beforeGet = { CompletableDeferred<Unit>().await() }
        model.askReloadConflict()
        model.reloadConflict()
        runCurrent()
        assertEquals(NotesOperation.Reload, model.state.value.operation)
        assertTrue(model.onBackRequested())
        advanceUntilIdle()
        assertNull(model.state.value.operation)
        assertTrue(model.state.value.conflict)
        assertEquals("本地", model.state.value.draft!!.content)
        assertEquals("读取已取消", model.state.value.message)
    }

    @Test fun savingBlocksDuplicateSubmitEditingAndBackUntilCommitReturns() = runTest(dispatcher) {
        advanceUntilIdle()
        model.request(NotesDestination.Create)
        model.changeContent("原内容")
        val wait = CompletableDeferred<Unit>()
        api.beforeWrite = { wait.await() }
        model.save()
        runCurrent()
        model.save()
        model.changeContent("不应覆盖")
        assertTrue(model.onBackRequested())
        assertEquals(NotesOperation.Save, model.state.value.operation)
        assertEquals("原内容", model.state.value.draft!!.content)
        wait.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, api.records.size)
        assertEquals("原内容", api.records.values.single().content)
    }

    @Test fun trashBackReturnsToActiveBeforeLeavingRoute() = runTest(dispatcher) {
        advanceUntilIdle()
        model.request(NotesDestination.Collection(NotesCollection.Trash))
        assertTrue(model.onBackRequested())
        assertEquals(NotesCollection.Active, model.state.value.collection)
        assertFalse(model.onBackRequested())
    }

    @Test fun editorIdentitySurvivesChangesAndSaveButChangesOnExplicitReload() = runTest(dispatcher) {
        advanceUntilIdle()
        model.request(NotesDestination.Create)
        val originalKey = model.state.value.draft!!.editorKey
        model.changeContent("中文👩‍💻")
        assertEquals(originalKey, model.state.value.draft!!.editorKey)
        model.save()
        advanceUntilIdle()
        assertEquals(originalKey, model.state.value.draft!!.editorKey)
        val id = model.state.value.draft!!.original!!.id
        model.changeContent("本地")
        api.externalEdit(id, "另一份内容")
        model.save()
        advanceUntilIdle()
        model.askReloadConflict()
        model.reloadConflict()
        advanceUntilIdle()
        assertTrue(originalKey != model.state.value.draft!!.editorKey)
        assertEquals("另一份内容", model.state.value.draft!!.content)
    }

    @Test fun restoredDirtyDraftKeepsOriginalOccRevision() = runTest(dispatcher) {
        val original = api.create(CreateNote("original", "body"))
        val restored = NoteDraft(original, "local", "未保存👩‍💻", listOf("tag"), 19)
        val recovered = NotesViewModel(api, dispatcher, initialDraft = restored)
        advanceUntilIdle()
        assertEquals("未保存👩‍💻", recovered.state.value.draft!!.content)
        assertTrue(recovered.state.value.draft!!.isDirty)
        recovered.save()
        advanceUntilIdle()
        assertEquals(original.updatedAt, api.lastEdit!!.expectedUpdatedAt)
        assertEquals(19L, recovered.state.value.draft!!.editorKey)
    }
}
