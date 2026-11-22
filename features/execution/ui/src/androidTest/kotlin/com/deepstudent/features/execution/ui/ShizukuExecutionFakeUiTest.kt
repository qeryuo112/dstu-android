package com.deepstudent.features.execution.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.deepstudent.design.DstuTheme
import com.deepstudent.services.execution.api.ExecutionAudit
import com.deepstudent.services.execution.api.ExecutionCommand
import com.deepstudent.services.execution.api.ExecutionFailureCode
import com.deepstudent.services.execution.api.ExecutionIdentity
import com.deepstudent.services.execution.api.ExecutionOutput
import com.deepstudent.services.execution.api.ExecutionReceipt
import com.deepstudent.services.execution.api.ExecutionRequest
import com.deepstudent.services.execution.api.ExecutionResult
import com.deepstudent.services.execution.api.ExecutionSystem
import com.deepstudent.services.execution.api.ShizukuConnectionState
import com.deepstudent.services.execution.api.ShizukuStatus
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ShizukuExecutionFakeUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val host = ShizukuUiTestHost()

    @Before
    fun start() = host.start()

    @After
    fun close() = host.close()

    @Test
    fun readyStateShowsBothUidsAndTypedCommandResult() {
        val fake = FakeSystem()
        render(fake)
        compose.onNodeWithText("已连接").assertIsDisplayed()
        compose.onNodeWithText("后端 UID").assertIsDisplayed()
        compose.onNodeWithText("实际 UID").assertIsDisplayed()
        compose.onNodeWithTag("shizuku-verify").performClick()
        compose.onNodeWithText("退出码 0 · 3 ms · UID 2000").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("uid=2000(shell)").performScrollTo().assertIsDisplayed()
        assertEquals(ExecutionCommand.Identity, fake.lastRequest?.command)
    }

    @Test
    fun installationRunningRevokedDeadAndClosedStatesAreDistinct() {
        val fake = FakeSystem(
            initial = ShizukuStatus(
                state = ShizukuConnectionState.NOT_INSTALLED,
                diagnostic = ExecutionFailureCode.NOT_INSTALLED,
            ),
        )
        render(fake)
        compose.onNodeWithText("未安装 Shizuku").assertIsDisplayed()
        compose.onNodeWithTag("shizuku-verify").assertIsNotEnabled()
        compose.runOnIdle { fake.publish(ShizukuStatus(state = ShizukuConnectionState.NOT_RUNNING)) }
        compose.onNodeWithText("Shizuku 服务未运行").assertIsDisplayed()
        compose.runOnIdle { fake.publish(ShizukuStatus(state = ShizukuConnectionState.BINDER_DEAD)) }
        compose.onNodeWithText("连接已断开").assertIsDisplayed()
        compose.runOnIdle { fake.publish(ShizukuStatus(state = ShizukuConnectionState.CLOSED)) }
        compose.onNodeWithText("执行服务已关闭").assertIsDisplayed()
    }

    @Test
    fun runningCommandCanBeCancelledAndReportsCancelledState() {
        val fake = FakeSystem().apply { hold = true }
        render(fake)
        compose.onNodeWithTag("shizuku-verify").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("shizuku-cancel").assertIsDisplayed().performClick()
        compose.onNodeWithText("执行失败：执行已取消").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("shizuku-verify").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("shizuku-cancel").assertDoesNotExist()
    }

    @Test
    fun permissionAndRevokedStatesOnlyOfferTheirOwnActions() {
        val fake = FakeSystem(
            initial = ShizukuStatus(
                state = ShizukuConnectionState.PERMISSION_REQUIRED,
                backendUid = 2000,
            ),
        )
        render(fake)
        compose.onNodeWithTag("shizuku-request-permission").performClick()
        compose.onNodeWithText("等待授权").assertIsDisplayed()
        assertEquals(1, fake.permissionCalls)
        compose.runOnIdle {
            fake.publish(
                ShizukuStatus(
                    state = ShizukuConnectionState.REVOKED,
                    backendUid = 2000,
                ),
            )
        }
        compose.onNodeWithText("授权已撤销").assertIsDisplayed()
        compose.onNodeWithTag("shizuku-request-permission").assertIsEnabled()
        compose.onNodeWithTag("shizuku-verify").assertIsNotEnabled()
        compose.onNodeWithTag("shizuku-packages").assertIsNotEnabled()
        compose.onNodeWithTag("shizuku-request-permission").performClick()
        assertEquals(2, fake.permissionCalls)
    }

    @Test
    fun binderDeathAndUntrustedRootStateNeverOfferExecution() {
        val fake = FakeSystem(initial = ShizukuStatus(state = ShizukuConnectionState.BINDER_DEAD, backendUid = 0))
        render(fake)
        compose.onNodeWithText("连接已断开").assertIsDisplayed()
        compose.onNodeWithTag("shizuku-verify").assertIsNotEnabled()
        compose.onNodeWithTag("shizuku-packages").assertIsNotEnabled()
        compose.onNodeWithTag("shizuku-request-permission").assertDoesNotExist()
        compose.runOnIdle {
            fake.publish(ShizukuStatus(state = ShizukuConnectionState.UNTRUSTED_BACKEND, backendUid = 10123))
        }
        compose.onNodeWithText("后端身份不受信任").assertIsDisplayed()
        compose.onNodeWithTag("shizuku-verify").assertIsNotEnabled()
    }

    @Test
    fun rootIdentityIsShownAsRootNotShellAndPackageQueryStaysTyped() {
        val fake = FakeSystem(initial = ShizukuStatus(state = ShizukuConnectionState.READY, backendUid = 0, observedUid = 0))
        render(fake)
        compose.onNodeWithText("root").assertIsDisplayed()
        compose.onNodeWithTag("shizuku-packages").performClick()
        compose.runOnIdle { assertEquals(ExecutionCommand.PackageList, fake.lastRequest?.command) }
    }

    private fun render(fake: FakeSystem) {
        host.setContent { DstuTheme { ShizukuExecutionRoute(fake) } }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("shizuku-status").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }

    private class FakeSystem(
        initial: ShizukuStatus = ShizukuStatus(
            state = ShizukuConnectionState.READY,
            installedVersionName = "13.5.4",
            serviceVersion = 13,
            backendUid = 2000,
            observedUid = 2000,
        ),
    ) : ExecutionSystem {
        private val state = MutableStateFlow(initial)
        override val status: StateFlow<ShizukuStatus> = state
        var lastRequest: ExecutionRequest? = null
        var permissionCalls = 0
        var hold = false

        fun publish(value: ShizukuStatus) {
            state.value = value
        }

        override fun start() = Unit

        override fun stop() = Unit

        override suspend fun refresh() = Unit

        override fun requestPermission(onResult: (Boolean) -> Unit) {
            permissionCalls++
            onResult(true)
        }

        override suspend fun execute(request: ExecutionRequest): ExecutionResult {
            lastRequest = request
            if (hold) awaitCancellation()
            val uid = checkNotNull(state.value.backendUid)
            val output = when (request.command) {
                ExecutionCommand.Identity -> "uid=$uid(${if (uid == 0) "root" else "shell"})"
                ExecutionCommand.PackageList -> "package:android"
            }
            return ExecutionResult.Success(
                ExecutionReceipt(
                    audit = ExecutionAudit(
                        request.taskId,
                        request.callerId,
                        request.command.commandId,
                        request.command.argv,
                    ),
                    identity = ExecutionIdentity(uid, if (request.command === ExecutionCommand.Identity) uid else null),
                    output = ExecutionOutput(output, "", false, false),
                    exitCode = 0,
                    durationMillis = 3,
                ),
            )
        }

        override fun close() = Unit
    }
}
