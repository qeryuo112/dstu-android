package com.deepstudent.services.runtime.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.services.runtime.api.BusinessCompletion
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.TaskCursor
import com.deepstudent.services.runtime.api.TaskKind
import com.deepstudent.services.runtime.api.TaskSubmission
import com.deepstudent.services.runtime.backend.LedgerCommand
import com.deepstudent.services.runtime.backend.LedgerResult
import com.deepstudent.services.runtime.backend.leaseToken
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeLedgerAndroidTest {
    @Test
    fun bundledSqlitePersistsEventsCheckpointAndReceiptAcrossReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.cacheDir.resolve("runtime-s1-08-device.db")
        val now = Instant.parse("2026-11-16T00:00:00Z")
        val taskId = TaskId.parse("device-task")
        try {
            file.delete()
            DatabaseFactory.openLocal(file).use { database ->
                val ledger = SqlTaskLedger(database)
                val task = ledger.create(taskId, TaskSubmission(TaskKind.parse("device.test")), now)
                assertTrue(task.inserted)
                val claimed = ledger.apply(taskId, LedgerCommand.Claim("device-owner", now.plusSeconds(30)), now)
                val token = requireNotNull((claimed as LedgerResult.Applied).snapshot.leaseToken())
                ledger.apply(taskId, LedgerCommand.Transition(token, RuntimeState.RUNNING), now)
                ledger.apply(taskId, LedgerCommand.Checkpoint(token, JsonValue.text("checkpoint")), now)
                val fact = BusinessCompletion("device-fact", taskId, 1, RuntimeState.COMPLETED, "receipt", now)
                assertTrue(ledger.apply(taskId, LedgerCommand.Reconcile(fact, token), now) is LedgerResult.Applied)
            }
            DatabaseFactory.openLocal(file).use { database ->
                val ledger = SqlTaskLedger(database)
                val snapshot = requireNotNull(ledger.snapshot(taskId))
                assertEquals(RuntimeState.COMPLETED, snapshot.state)
                assertEquals(JsonValue.text("checkpoint"), snapshot.execution.checkpoint)
                assertEquals("receipt", snapshot.execution.receipt)
                assertEquals(snapshot.cursor, ledger.events(taskId, TaskCursor.ZERO).last().cursor)
            }
        } finally {
            file.delete()
            file.resolveSibling("${file.name}-wal").delete()
            file.resolveSibling("${file.name}-shm").delete()
        }
    }
}
