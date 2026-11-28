package com.deepstudent.features.chat.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.platform.database.BundledMigrationSource
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SqlChatRepositoryAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "s301-chat-${System.nanoTime()}").apply { mkdirs() }
    private val contract = ChatRepositorySqliteContract(root, BundledMigrationSource(context))

    @After fun cleanup() {
        root.deleteRecursively()
    }

    @Test fun restartAndProjection() = contract.restartAndProjection()

    @Test fun partialUpdateAndReplay() = contract.partialUpdateAndReplay()

    @Test fun rollbackOnFailedRow() = contract.rollbackOnFailedRow()

    @Test fun cancellationBeforeCommitRollsBack() = contract.cancellationBeforeCommitRollsBack()

    @Test fun readSnapshotIsConsistent() = contract.readSnapshotIsConsistent()
}
