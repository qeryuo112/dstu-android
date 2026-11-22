package com.deepstudent.app.notes

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.storage.MainStorageResources
import com.deepstudent.design.DstuMode
import com.deepstudent.design.DstuTheme
import com.deepstudent.features.notes.ui.NotesRoute
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class NotesUiRestartDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val root get() = File(instrumentation.targetContext.cacheDir, "s1-12-ui-restart")

    @Test(timeout = 90_000)
    fun writeWithRealUi(): Unit = runBlocking {
        selected("writeWithRealUi")
        root.deleteRecursively()
        val storage = open()
        val host = NotesUiHost()
        try {
            host.start()
            host.setContent { DstuTheme { Box(Modifier.width(390.dp).fillMaxSize()) { NotesRoute(storage.notes, {}) } } }
            await("暂无笔记")
            compose.onNodeWithContentDescription("新建笔记").performClick()
            compose.onNodeWithTag("note-title").performTextClearance()
            compose.onNodeWithTag("note-title").performTextInput(TITLE)
            compose.onNodeWithTag("note-content").performTextInput(BODY)
            compose.onNodeWithContentDescription("保存笔记").performClick()
            await("已保存")
            val saved = storage.notes.list().single()
            assertEquals(TITLE, saved.title)
            assertEquals(BODY, saved.content)
            println("s1.notes.ui.saved pid=${android.os.Process.myPid()} bytes=${saved.content.toByteArray().size}")
        } finally { host.close(); storage.close() }
    }

    @Test(timeout = 90_000)
    fun readAfterHostRestart(): Unit = runBlocking {
        selected("readAfterHostRestart")
        val storage = open()
        val host = NotesUiHost()
        var mode by mutableStateOf(DstuMode.Light)
        var phone by mutableStateOf(true)
        try {
            val saved = storage.notes.list().single()
            assertEquals(BODY, saved.content)
            host.start()
            host.setContent {
                DstuTheme(mode) {
                    Box(if (phone) Modifier.width(390.dp).fillMaxSize() else Modifier.fillMaxSize()) {
                        NotesRoute(storage.notes, {})
                    }
                }
            }
            await(TITLE)
            compose.onNodeWithText(TITLE).performClick()
            await("已保存")
            compose.onNodeWithTag("note-content").assertTextContains(BODY)
            capture("phone-light")
            compose.runOnIdle { mode = DstuMode.Dark }
            capture("phone-dark")
            compose.runOnIdle { mode = DstuMode.Light; phone = false }
            capture("tablet-light")
            compose.runOnIdle { mode = DstuMode.Dark }
            capture("tablet-dark")
            compose.onNodeWithContentDescription("删除笔记").performClick()
            compose.onNodeWithText("删除", substring = false).performClick()
            compose.waitUntil(10_000) { runBlocking { storage.notes.list().isEmpty() } }
            compose.onNodeWithContentDescription("恢复笔记").performClick()
            await("已保存")
            assertEquals(BODY, storage.notes.list().single().content)
            println("s1.notes.ui.reopened pid=${android.os.Process.myPid()} persisted=true deleteRestore=true")
        } finally { host.close(); storage.close() }
        root.deleteRecursively()
    }

    private fun await(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun capture(name: String) {
        val image = compose.onNodeWithTag("notes-screen").captureToImage().asAndroidBitmap()
        File(instrumentation.targetContext.cacheDir, "s1-notes-$name.png").outputStream().use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun open(): MainStorageResources = MainStorageResources.open(object : ContextWrapper(instrumentation.targetContext) {
        override fun getFilesDir(): File = root
    })

    private fun selected(method: String) {
        val selection = InstrumentationRegistry.getArguments().getString("class").orEmpty()
        assumeTrue("Host restart step must be selected explicitly", selection.endsWith("#$method"))
    }

    private companion object {
        const val TITLE = "线性代数与中文笔记 📝"
        const val BODY = "# 特征值与特征向量\n\n定义：若 Ax = λx，则 λ 是特征值。\n\n组合 emoji：👩‍💻 / 👨‍👩‍👧‍👦 / é。\n"
    }
}
