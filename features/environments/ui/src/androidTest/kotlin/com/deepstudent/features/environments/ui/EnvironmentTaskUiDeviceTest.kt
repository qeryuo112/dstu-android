package com.deepstudent.features.environments.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.deepstudent.design.DstuTheme
import com.deepstudent.features.environments.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class EnvironmentTaskUiDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val host = EnvironmentTestHost()
    @Before fun start() = host.start()
    @After fun close() = host.close()

    @Test fun lowSpaceRetryBusyCancellationAndObserverReentryUseOnlyCommands() {
        val manager = TaskUiFakeManager()
        val visible = mutableStateOf(true)
        host.setContent {
            DstuTheme {
                if (visible.value) EnvironmentsRoute(manager, { error("low space retry must not pick or open archives") }, { visible.value = false })
            }
        }
        compose.onNodeWithText("空间不足，请清理后继续").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("environment-retry").performScrollTo().performClick()
        compose.waitUntil(10_000) { manager.retries == 1 }
        compose.onNodeWithTag("environment-import").assertIsNotEnabled()
        compose.onNodeWithText("安全解包 2/8").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭环境安装").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, manager.cancels); visible.value = true }
        compose.onNodeWithText("安全解包 2/8").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("environment-cancel").performScrollTo().performClick()
        compose.waitUntil(10_000) { manager.cancels == 1 }
        compose.onNodeWithText("已取消").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("environment-import").assertIsEnabled()
    }

    @Test fun failedRootfsWithOldActiveCannotSubmitPackageMutation() {
        val manager = TaskUiFakeManager()
        manager.state.value = manager.state.value.copy(activeGeneration = "previous-active", runtime = manager.state.value.runtime.copy(
            recipes = listOf(PackageRecipeState("git", "Git", PackageKind.APT, PackageRecipeStatus.INSTALLED))))
        host.setContent { DstuTheme { EnvironmentsRoute(manager, { null }, {}) } }
        compose.onNodeWithTag("install-git").performScrollTo().assertIsNotEnabled()
    }
}

private class TaskUiFakeManager : EnvironmentManager {
    override val state = MutableStateFlow(EnvironmentSnapshot(phase = EnvironmentPhase.FAILED,
        runtime = EnvironmentRuntimeState(failure = EnvironmentFailure(EnvironmentFailureCode.LOW_SPACE, "空间不足，请清理后继续", true), recoverable = true)))
    @Volatile var retries = 0
    @Volatile var cancels = 0
    override suspend fun installRootfs(manifest: RootfsManifest, source: RootfsArchiveSource): EnvironmentTaskId = error("not a UI operation")
    override suspend fun installPackage(recipeId: String): EnvironmentTaskId = error("must not install without ready rootfs")
    override suspend fun retry(source: RootfsArchiveSource?): EnvironmentTaskId {
        retries++
        val id = EnvironmentTaskId("task-fake-ui-retry")
        state.value = EnvironmentSnapshot(phase = EnvironmentPhase.EXTRACTING, runtime = EnvironmentRuntimeState(taskId = id,
            progress = EnvironmentProgress(EnvironmentStage.EXTRACT, completedEntries = 2, totalEntries = 8)))
        return id
    }
    override suspend fun cancel(taskId: EnvironmentTaskId) {
        cancels++
        state.value = state.value.copy(phase = EnvironmentPhase.CANCELLED)
    }
}
