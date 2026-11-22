package com.deepstudent.features.environments.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.deepstudent.design.DstuTheme
import com.deepstudent.features.environments.api.EnvironmentPhase
import com.deepstudent.features.environments.api.EnvironmentRuntimeState
import com.deepstudent.features.environments.api.EnvironmentSnapshot
import com.deepstudent.features.environments.api.InstalledVersion
import com.deepstudent.features.environments.api.PackageKind
import com.deepstudent.features.environments.api.PackageRecipeState
import com.deepstudent.features.environments.api.PackageRecipeStatus
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class EnvironmentsFakeUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val host = EnvironmentTestHost()

    @Before
    fun start() = host.start()

    @After
    fun close() = host.close()

    @Test
    fun downloadAndImportButtonsSubmitDistinctActionsAndDisableWhenBusy() {
        var downloads = 0
        var imports = 0
        val busy = androidx.compose.runtime.mutableStateOf(false)
        host.setContent {
            DstuTheme {
                EnvironmentsScreen(
                    state = EnvironmentUiState(busy = busy.value, downloadAvailable = true),
                    actions = EnvironmentActions(
                        installRootfs = { imports++ },
                        downloadRootfs = { downloads++ },
                        retry = {}, cancel = {}, installRecipe = {}, dismissFailure = {}, close = {},
                    ),
                )
            }
        }
        compose.onNodeWithTag("environment-download").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("environment-import").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, downloads)
        assertEquals(1, imports)
        compose.runOnIdle { busy.value = true }
        compose.onNodeWithTag("environment-download").assertIsNotEnabled()
        compose.onNodeWithTag("environment-import").assertIsNotEnabled()
    }

    @Test
    fun readyEnvironmentShowsVersionsAndPackageAction() {
        var installedRecipe: String? = null
        val recipe = PackageRecipeState(
            id = "project-venv",
            title = "Project Python venv",
            kind = PackageKind.VENV_PIP,
            status = PackageRecipeStatus.READY_TO_INSTALL,
        )
        val snapshot = EnvironmentSnapshot(
            phase = EnvironmentPhase.READY,
            rootfsId = "ubuntu-noble-aarch64-pd-v4.18.0",
            rootfsSha256 = "91acaa786b8e2fbba56a9fd0f8a1188cee482b5c7baeed707b29ddaa9a294daa",
            activeGeneration = "ubuntu-noble-aarch64-pd-v4.18.0",
            runtime = EnvironmentRuntimeState(
                recipes = listOf(
                    PackageRecipeState(
                        id = "git",
                        title = "Git",
                        kind = PackageKind.APT,
                        status = PackageRecipeStatus.INSTALLED,
                        versions = listOf(InstalledVersion("git", "1:2.43.0-1ubuntu7", "apt")),
                    ),
                    recipe,
                ),
            ),
        )
        host.setContent {
            DstuTheme {
                EnvironmentsScreen(
                    state = EnvironmentUiState(snapshot),
                    actions = EnvironmentActions(
                        installRootfs = {},
                        downloadRootfs = {},
                        retry = {},
                        cancel = {},
                        installRecipe = { installedRecipe = it },
                        dismissFailure = {},
                        close = {},
                    ),
                )
            }
        }

        compose.onNodeWithText("环境可用").assertIsDisplayed()
        compose.onNodeWithText("Git").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("git 1:2.43.0-1ubuntu7").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("install-project-venv").performScrollTo().performClick()
        assertEquals("project-venv", installedRecipe)
    }
}
