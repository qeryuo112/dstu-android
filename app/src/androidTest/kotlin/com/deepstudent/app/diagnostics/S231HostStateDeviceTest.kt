package com.deepstudent.app.diagnostics

import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class S231HostStateDeviceTest {
    private val compose = createEmptyComposeRule()
    private val host = S231AppTestHost()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(S231IsolatedAppRule()).around(compose).around(host)

    @Test
    fun openingDiagnosticsDoesNotDisposeUnderlyingWorkspace() {
        var disposed = 0
        host.setContent {
            S231Host {
                DisposableEffect(Unit) { onDispose { disposed++ } }
                Text("workspace-test-content")
            }
        }
        compose.awaitDiagnosticText("诊断与错误")
        compose.onNodeWithText("诊断与错误").performClick()
        compose.awaitDiagnosticText("分享诊断文件")
        compose.onNodeWithText("分享诊断文件").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, disposed) }
        compose.onNodeWithText("返回应用").performClick()
        compose.onNodeWithText("workspace-test-content").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, disposed) }
    }
}
