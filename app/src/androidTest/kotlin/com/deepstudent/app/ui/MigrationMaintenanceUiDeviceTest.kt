package com.deepstudent.app.ui

import android.content.ContextWrapper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.editor.EditorTestHost
import com.deepstudent.app.graph.MigrationRetryOwner
import com.deepstudent.app.graph.MigrationRetryState
import com.deepstudent.app.storage.MainStorageResources
import com.deepstudent.app.workspace.createWorkspacePorts
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.features.chat.api.ChatStreamRequest
import com.deepstudent.features.chat.api.ChatStreamSnapshot
import com.deepstudent.features.chat.api.ChatStreams
import com.deepstudent.platform.database.BundledMigrationSource
import com.deepstudent.platform.database.DatabaseRegistry
import com.deepstudent.platform.database.MigrationMaintenance
import com.deepstudent.platform.database.MigrationMaintenanceException
import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlValue
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationMaintenanceUiDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = 120_000)
    fun startupSourceDriftShowsDiagnosisAndResumesAcrossRecreate() {
        val base = instrumentation.targetContext
        val root = Files.createTempDirectory(base.cacheDir.toPath(), "s203-ui-").toFile()
        val context = object : ContextWrapper(base) { override fun getFilesDir() = root }
        val file = root.resolve("active/databases/vfs.db")
        check(file.parentFile!!.mkdirs())
        val secret = "private-old-row-87dd"
        DatabaseRegistry.openLocal(file).use { db ->
            db.execute("CREATE TABLE maintenance_marker(value TEXT NOT NULL)")
            db.execute("INSERT INTO maintenance_marker VALUES(?)", listOf(SqlValue.Text(secret)))
        }
        val source = BundledMigrationSource(context)
        val attempts = AtomicInteger()
        val allowSource = AtomicBoolean(false)
        val changedFile = "config/schema/history/vfs/V20260130__init.sql"
        val enteredRetry = CountDownLatch(1)
        val resumeRetry = CountDownLatch(1)
        val guarded = MigrationSource { path ->
            if (path == "config/schema/final-lock.json" && attempts.incrementAndGet() == 3) {
                enteredRetry.countDown()
                check(resumeRetry.await(30, TimeUnit.SECONDS))
            }
            source.read(path).let { if (path == changedFile && !allowSource.get()) it + 10 else it }
        }
        val maintenance = MigrationMaintenance(file, SharedDatabase.VFS, guarded)
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val retries = MigrationRetryOwner(appScope, maintenance::query) { maintenance.retry() }
        val opened = AtomicReference<MainStorageResources?>()
        val host = EditorTestHost()
        try {
            host.start()
            fun installScreen() = host.setContent {
                MainScreen(
                    loadWorkspace = {
                        withContext(Dispatchers.IO) {
                            val storage = MainStorageResources.open(context, maintenance)
                            opened.set(storage)
                            createWorkspacePorts(context, storage.notes, NoChat)
                        }
                    },
                    retryState = retries.state,
                    retryMigration = retries::start,
                )
            }
            installScreen()
            awaitTag("migration-maintenance")
            compose.onNodeWithText("数据库：vfs").assertIsDisplayed()
            compose.onNodeWithText("目标版本：未知").assertIsDisplayed()
            compose.onNodeWithText("SOURCE", substring = true).assertIsDisplayed()
            compose.onNodeWithText("SQL_BYTES_DRIFT", substring = true).assertIsDisplayed()
            compose.onNodeWithText("失败迁移版本：20260130").assertIsDisplayed()
            compose.onNodeWithText(secret, substring = true).assertDoesNotExist()
            compose.onNodeWithText(root.absolutePath, substring = true).assertDoesNotExist()
            assertEquals(1, attempts.get())
            // A competing startup caller receives the same failure without starting a silent retry.
            val firstFailure = maintenance.query()
            try { maintenance.open(); error("expected latched failure") }
            catch (failure: MigrationMaintenanceException) { assertTrue(failure.status === firstFailure) }
            assertEquals(1, attempts.get())

            compose.onNodeWithTag("migration-retry").performScrollTo().performClick()
            compose.waitUntil(20_000) { retries.state.value is MigrationRetryState.Finished && attempts.get() == 2 }
            compose.onNodeWithTag("migration-maintenance").assertIsDisplayed()
            allowSource.set(true)

            // Pause an explicit retry inside its real source read and recreate the Activity.
            compose.onNodeWithTag("migration-retry").performScrollTo().performClick()
            assertTrue(enteredRetry.await(20, TimeUnit.SECONDS))
            val first = host.activity
            instrumentation.runOnMainSync { first.recreate() }
            compose.waitUntil(20_000) { runCatching { host.activity !== first }.getOrDefault(false) }
            installScreen()
            resumeRetry.countDown()
            awaitTag("workspace-panels")
            // The recreated load may wait for the in-flight retry and use the first governed reopen,
            // or it may observe failure first and be restarted after the retry finishes.
            assertTrue(attempts.get() in 4..5)
            val db = checkNotNull(opened.get()).databaseForSync()
            assertEquals(0, db.migrationReport?.appliedCount)
            assertEquals(listOf(secret), db.query("SELECT value FROM maintenance_marker") { it.text(0) })
        } finally {
            resumeRetry.countDown()
            host.close()
            opened.get()?.close()
            appScope.cancel()
            root.deleteRecursively()
        }
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(60_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }
}

private object NoChat : ChatStreams {
    override suspend fun start(request: ChatStreamRequest): TaskId = error("chat not configured")
    override fun observe(taskId: TaskId): Flow<ChatStreamSnapshot> = emptyFlow()
    override suspend fun cancel(taskId: TaskId) = Unit
}
