package com.deepstudent.features.notes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.deepstudent.design.DstuTheme
import com.deepstudent.design.DstuMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NotesScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val host = NotesTestHost()

    @Before fun start() = host.start()
    @After fun close() = host.close()

    @Test(timeout = 90_000) fun darkScreenSuppliesReadableForegroundWithoutAnOuterSurface() {
        var observed = androidx.compose.ui.graphics.Color.Unspecified
        host.setContent {
            DstuTheme(DstuMode.Dark) {
                NotesScreen(NotesUiState(listStatus = NotesListStatus.Ready,
                    draft = NoteDraft(null, "标题", "正文", emptyList())), {}, editorContent = { _, _, _, _ ->
                    observed = androidx.compose.material3.LocalContentColor.current
                })
            }
        }
        compose.runOnIdle {
            org.junit.Assert.assertEquals(com.deepstudent.design.DstuTokens.resolve(DstuMode.Dark).colors.foreground, observed)
        }
    }

    @Test(timeout = 90_000) fun phoneCrudAndDiscardConfirmation() {
        val api = FakeNotes()
        host.setContent { DstuTheme { Box(Modifier.size(390.dp, 760.dp)) { NotesRoute(api, onExit = {}) } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("暂无笔记").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("新建笔记").performClick()
        compose.onNodeWithTag("note-title").performTextClearance()
        compose.onNodeWithTag("note-title").performTextInput("中文 📝")
        compose.onNodeWithTag("note-content").performTextInput("正文 👩‍👩‍👧‍👦")
        compose.onNodeWithContentDescription("保存笔记").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("note-content").performTextInput("改动")
        compose.onNodeWithContentDescription("返回笔记列表").performClick()
        compose.onNodeWithText("放弃未保存的修改？").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithContentDescription("保存笔记").performClick()
        compose.waitUntil(10_000) { api.records.values.first().content.contains("改动") }
        compose.onNodeWithContentDescription("删除笔记").performClick()
        compose.onNodeWithText("删除", substring = false).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("恢复笔记").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("恢复笔记").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("删除笔记").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("返回笔记列表").performClick()
        compose.onNodeWithText("中文 📝").assertIsDisplayed()
        compose.onNodeWithText("中文 📝").performClick()
        compose.onNodeWithTag("note-content").assertIsDisplayed()
    }

    @Test(timeout = 90_000) fun rotationAndRecreationKeepDirtyDraftAndBackStack() {
        val api = FakeNotes()
        val item = runBlocking { api.create(com.deepstudent.features.notes.api.CreateNote("旋转笔记", "原文")) }
        host.setContent { DstuTheme { Box(Modifier.size(390.dp, 760.dp)) { NotesRoute(api, onExit = {}) } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("note-row-${item.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("note-row-${item.id}").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("note-content").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("note-content").performTextInput("草稿")
        host.rotate()
        compose.onNodeWithTag("note-content").assertTextContains("草稿原文")
        host.recreate()
        compose.onNodeWithTag("note-content").assertTextContains("草稿原文")
        compose.onNodeWithContentDescription("返回笔记列表").performClick()
        compose.onNodeWithText("放弃未保存的修改？").assertIsDisplayed()
        compose.onNodeWithText("放弃修改").performClick()
        compose.onNodeWithTag("note-row-${item.id}").assertIsDisplayed()
    }

    @Test(timeout = 90_000) fun wideScreenListAndDetailRemainTogether() {
        val api = FakeNotes()
        val item = runBlocking { api.create(com.deepstudent.features.notes.api.CreateNote("平板笔记", "正文")) }
        host.setContent {
            DstuTheme {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val current = LocalDensity.current
                    val widthPixels = with(current) { maxWidth.toPx() }
                    CompositionLocalProvider(LocalDensity provides Density(widthPixels / 900f, current.fontScale)) {
                        NotesRoute(api, onExit = {})
                    }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("note-row-${item.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("note-row-${item.id}").performClick()
        compose.onNodeWithTag("notes-list").assertIsDisplayed()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("note-content").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("note-content").assertIsDisplayed()
    }

    @Test(timeout = 90_000) fun stateSurfacesShowRetryCancelAndConflict() {
        var state by mutableStateOf(NotesUiState(listStatus = NotesListStatus.Loading))
        host.setContent { DstuTheme { Box(Modifier.size(390.dp, 760.dp)) { NotesScreen(state, {}) } } }
        compose.onNodeWithText("正在加载笔记").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(listStatus = NotesListStatus.Error) }
        compose.onNodeWithText("重试").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(listStatus = NotesListStatus.Cancelled) }
        compose.onNodeWithText("加载已取消").assertIsDisplayed()
        compose.runOnIdle {
            state = state.copy(
                listStatus = NotesListStatus.Ready, draft = NoteDraft(note("id", "标题", "正文", emptyList(), "1"), "标题", "本地", emptyList()),
                feedback = NotesFeedback(conflict = true, message = "笔记已在别处修改，本地内容尚未保存"),
            )
        }
        compose.onNodeWithText("重新加载").assertIsDisplayed()
        compose.onNodeWithContentDescription("保存笔记").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(operation = NotesOperation.Save, feedback = NotesFeedback()) }
        compose.onNodeWithText("正在保存").assertIsDisplayed()
    }
}
