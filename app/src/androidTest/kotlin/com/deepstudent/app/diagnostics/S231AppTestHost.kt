package com.deepstudent.app.diagnostics

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.editor.EditorTestHost
import org.junit.rules.ExternalResource

/** Start the real, isolation-checked Activity through the OEM-compatible foreground harness. */
internal class S231AppTestHost : ExternalResource() {
    private val host = EditorTestHost()
    val activity get() = host.activity

    override fun before() {
        try {
            host.start()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        } catch (failure: Throwable) {
            host.close()
            throw failure
        }
    }

    override fun after() = host.close()

    fun setContent(content: @Composable () -> Unit) = host.setContent(content)
}

internal fun ComposeTestRule.awaitDiagnosticText(text: String) {
    waitUntil(20_000) {
        onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    }
}
