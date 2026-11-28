package com.deepstudent.app.runtime

import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.DstuApplication
import com.deepstudent.app.editor.EditorTestHost
import com.deepstudent.features.workspace.api.WorkspaceMode
import com.deepstudent.features.workspace.api.WorkspacePanelKeys
import com.deepstudent.features.workspace.api.WorkspaceState
import com.deepstudent.features.workspace.api.WorkspaceStatus
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * S3.08 official-shell acceptance for the developer LLM playground.
 *
 * Both cases drive the real MainActivity and its official workspace shell. Use the existing shell
 * launch fixture because API 36 instrumentation activity starts can stall on these devices.
 * No test replaces the Activity content or accesses any endpoint outside IPv4 loopback.
 */
@RunWith(AndroidJUnit4::class)
class S308PlaygroundDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = DstuApplication.from(instrumentation.targetContext)
    private var host: EditorTestHost? = null

    @Before
    fun startOfficialShell() {
        assertEquals(ISOLATED_APPLICATION_ID, instrumentation.targetContext.packageName)
        val created = EditorTestHost()
        host = created
        created.start()
        keepScreenOn()
    }

    @After
    fun stopOfficialShell() {
        host?.close()
    }

    private fun keepScreenOn() {
        val activity = checkNotNull(host).activity
        instrumentation.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    @Test(timeout = 120_000)
    fun officialShellRestoresPlaygroundAndReturnsHome() {
        openPlayground()
        compose.onAllNodesWithText(PLAYGROUND_TITLE).fetchSemanticsNodes().also { nodes -> assertTrue(nodes.isNotEmpty()) }
        compose.onNodeWithTag("debug/models").assertExists()

        val opened = awaitState({ it.activePanel?.key?.value == PLAYGROUND_KEY }) { "playground selection" }
        assertEquals(PLAYGROUND_KEY, opened.activePanel?.key?.value)

        val currentHost = checkNotNull(host)
        val first = currentHost.activity
        instrumentation.runOnMainSync { first.recreate() }
        compose.waitUntil(WAIT_MS) { currentHost.activity !== first }
        keepScreenOn()

        awaitTag(SCREEN_TAG)
        val widthDp = currentHost.activity.resources.configuration.screenWidthDp
        val expectedMode = if (widthDp >= TABLET_WIDTH_DP) WorkspaceMode.TabletPanels else WorkspaceMode.PhoneStack
        val restored = awaitState({
            it.activePanel?.key?.value == PLAYGROUND_KEY && it.layout.mode == expectedMode
        }) { "playground selection restored as $expectedMode at ${widthDp}dp" }
        assertEquals(expectedMode, restored.layout.mode)
        assertEquals(PLAYGROUND_KEY, restored.activePanel?.key?.value)
        assertTrue(restored.layout.panels.any { it.key.value == PLAYGROUND_KEY })

        compose.onNodeWithTag(BACK_TAG).performClick()
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithTag(SCREEN_TAG).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
        val home = awaitState({ state ->
            state.status == WorkspaceStatus.Ready && state.layout.panels.none { it.key.value == PLAYGROUND_KEY } &&
                state.activePanel?.key == WorkspacePanelKeys.Notes
        }) { "home Notes without an active playground" }
        assertTrue(home.layout.panels.none { it.key.value == PLAYGROUND_KEY })
        assertEquals(WorkspacePanelKeys.Notes.value, home.activePanel?.key?.value)
        compose.onNodeWithTag("native-workspace").assertExists()
    }

    @Test(timeout = 180_000)
    fun playgroundUsesTheRealCatalogAndRelayTransport() {
        val peer = MockWebServer()
        try {
            peer.start(InetAddress.getByName(LOOPBACK), 0)
            peer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"object":"list","data":[{"id":"$MODEL_ID","object":"model","created":0,"owned_by":"local"}]}"""),
            )
            peer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(chatSse(MODEL_ID)),
            )

            openPlayground()
            val endpoint = "http://$LOOPBACK:${peer.port}/v1"

            compose.onNodeWithTag("model-playground-provider").performScrollTo().performTextInput(PROVIDER)
            compose.onNodeWithTag("model-playground-endpoint").performScrollTo().performTextInput(endpoint)
            compose.onNodeWithTag("model-playground-auth-mode").performScrollTo().performTextInput(AUTH_NONE)
            compose.onNodeWithTag("model-playground-fetch").performScrollTo().performClick()

            awaitTag(OPTION_TAG)
            compose.onNodeWithTag(OPTION_TAG).assertTextContains(MODEL_ID, substring = true)
            compose.onNodeWithTag("model-playground-model").assertTextContains(MODEL_ID, substring = true)

            compose.onNodeWithTag("model-playground-prompt").performTextInput(PROMPT)
            compose.onNodeWithTag("model-playground-send").performClick()
            compose.waitUntil(WAIT_MS) {
                runCatching {
                    compose.onNodeWithTag("model-playground-output")
                        .assertTextContains(REPLY_TEXT, substring = true)
                }.isSuccess
            }

            val catalog = peer.takeRequest(20, TimeUnit.SECONDS)
            assertNotNull(catalog)
            assertEquals("$LOOPBACK:${peer.port}", catalog!!.getHeader("Host"))
            assertTrue(catalog.path.orEmpty().startsWith("/v1/models"))
            assertNull(catalog.getHeader("Authorization"))

            val relay = peer.takeRequest(20, TimeUnit.SECONDS)
            assertNotNull(relay)
            assertEquals("$LOOPBACK:${peer.port}", relay!!.getHeader("Host"))
            assertTrue(relay.path.orEmpty().startsWith("/v1/chat/completions"))
            assertNull(relay.getHeader("Authorization"))
            val payload = relay.body.readUtf8()
            assertTrue(payload, payload.contains(MODEL_ID))
            assertTrue(payload, payload.contains(PROMPT))
            assertEquals(0, peer.requestCount - 2)
        } finally {
            peer.shutdown()
        }
    }

    private fun openPlayground() {
        awaitTag(LAUNCHER_TAG)
        awaitEnabled(LAUNCHER_TAG)
        compose.onNodeWithTag(LAUNCHER_TAG).performScrollTo().performClick()
        awaitTag(SCREEN_TAG)
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }

    private fun awaitEnabled(tag: String) {
        compose.waitUntil(WAIT_MS) {
            runCatching { compose.onNodeWithTag(tag).assertIsEnabled() }.isSuccess
        }
    }

    private fun awaitState(predicate: (WorkspaceState) -> Boolean, description: () -> String): WorkspaceState {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        var last: WorkspaceState? = null
        var failure: Throwable? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                val state = runBlocking { app.workspace().workspace.state.value }
                last = state
                if (state.status == WorkspaceStatus.Ready && predicate(state)) return state
            } catch (error: Throwable) {
                failure = error
            }
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for ${description()}; status=${last?.status}", failure)
    }

    private fun chatSse(model: String): String = listOf(
        """data: {"id":"chatcmpl-s308","object":"chat.completion.chunk","created":0,"model":"$model","choices":[{"index":0,"delta":{"role":"assistant","content":"local "},"finish_reason":null}]}""",
        """data: {"id":"chatcmpl-s308","object":"chat.completion.chunk","created":0,"model":"$model","choices":[{"index":0,"delta":{"content":"relay reply"},"finish_reason":null}]}""",
        """data: {"id":"chatcmpl-s308","object":"chat.completion.chunk","created":0,"model":"$model","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
        "data: [DONE]",
        "",
    ).joinToString("\n\n")

    private companion object {
        const val ISOLATED_APPLICATION_ID = "com.deepstudent.app.s2device"
        const val LAUNCHER_TAG = "workspace-open-llm-playground"
        const val SCREEN_TAG = "model-playground-screen"
        const val BACK_TAG = "model-playground-back"
        const val OPTION_TAG = "model-playground-option-0"
        const val PLAYGROUND_KEY = "llm-playground"
        const val PLAYGROUND_TITLE = "LLM Playground"
        const val PROVIDER = "custom"
        const val AUTH_NONE = "none"
        const val MODEL_ID = "local-s308-model"
        const val PROMPT = "local relay prompt"
        const val REPLY_TEXT = "local relay reply"
        const val LOOPBACK = "127.0.0.1"
        const val TABLET_WIDTH_DP = 840
        const val WAIT_MS = 30_000L
    }
}
