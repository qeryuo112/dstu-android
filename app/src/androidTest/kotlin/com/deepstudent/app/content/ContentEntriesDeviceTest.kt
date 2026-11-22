package com.deepstudent.app.content

import android.graphics.Bitmap
import android.content.ContextWrapper
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.editor.EditorTestHost
import com.deepstudent.app.ui.AppWorkspace
import com.deepstudent.design.DstuMode
import com.deepstudent.design.DstuTheme
import com.deepstudent.features.notes.api.CreateNote
import com.deepstudent.features.notes.ui.NotesRoute
import com.deepstudent.app.DstuApplication
import com.deepstudent.app.storage.MainStorageResources
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ContentEntriesDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val host = EditorTestHost()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Before fun start() = host.start()
    @After fun close() = host.close()

    @Test(timeout = 90_000) fun allEntriesRenderInteractAndKeepTheHiddenNoteDraft() = runBlocking {
        val root = File(instrumentation.targetContext.cacheDir, "s1-15-content-entries")
        root.deleteRecursively()
        val storage = MainStorageResources.open(object : ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir(): File = root
        })
        val notes = storage.notes
        val note = notes.create(CreateNote("S1.15 临时验收", "原文"))
        var mode by mutableStateOf(DstuMode.Light)
        try {
            host.setContent { DstuTheme(mode) { AppWorkspace { active -> NotesRoute(notes, {}, active = active) } } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("note-row-${note.id}").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("note-row-${note.id}").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("note-content").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("note-content").performTextInput("草稿")
            compose.onNodeWithTag("workspace-content").performClick()
            waitJs("document.querySelectorAll('.katex').length > 0")
            capture("formula")
            compose.onNodeWithTag("content-mode-diagram").performClick()
            waitJs("document.querySelectorAll('[data-dstu-diagram] svg').length > 0")
            capture("diagram")
            compose.onNodeWithTag("content-mode-card").performClick()
            waitJs("document.querySelector('#reveal') !== null")
            js("document.querySelector('#reveal').click(); document.querySelector('[data-dstu-answer]').click()")
            compose.onNodeWithTag("content-event").assertTextContains("回答：再复习")
            compose.runOnIdle { mode = DstuMode.Dark }
            waitJs("document.documentElement.classList.contains('night_mode') && getComputedStyle(document.body).backgroundColor !== 'rgb(255, 255, 255)'")
            capture("card-dark")
            compose.onNodeWithTag("workspace-browser").performClick()
            compose.onNodeWithContentDescription("Close").performClick()
            compose.onNodeWithTag("note-content").assertTextContains("草稿原文")
            instrumentation.sendStatus(2, Bundle().apply { putString("stage", "entries.draft-return.passed") })
        } finally { host.close(); storage.close(); root.deleteRecursively() }
    }

    private fun waitJs(script: String) {
        compose.waitUntil(20_000) { js(script) == "true" }
    }

    private fun js(script: String): String {
        val done = CountDownLatch(1)
        val result = AtomicReference<String>("")
        instrumentation.runOnMainSync {
            val view = webView(host.activity.window.decorView)
            if (view == null) done.countDown()
            else view.evaluateJavascript(script) { result.set(it); done.countDown() }
        }
        check(done.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    private fun webView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) webView(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun capture(name: String) {
        val file = File(instrumentation.targetContext.cacheDir, "s1-content-$name.png")
        val painted = CountDownLatch(1)
        instrumentation.runOnMainSync {
            checkNotNull(webView(host.activity.window.decorView)).postVisualStateCallback(1L, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { painted.countDown() }
            })
        }
        check(painted.await(5, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            val view = checkNotNull(webView(host.activity.window.decorView))
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }
}
