package com.deepstudent.features.chat.data

import com.deepstudent.platform.database.MigrationSource
import org.junit.After
import org.junit.Test
import java.nio.file.Files

class SqlChatRepositoryTest {
    private val root = Files.createTempDirectory("s301-chat-").toFile()
    private val project = generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
        .first { it.resolve("config/schema/final-lock.json").isFile }
    private val contract = ChatRepositorySqliteContract(root, MigrationSource { project.resolve(it).readBytes() })

    @After fun cleanup() {
        root.deleteRecursively()
    }

    @Test fun restartAndProjection() = contract.restartAndProjection()

    @Test fun partialUpdateAndReplay() = contract.partialUpdateAndReplay()

    @Test fun rollbackOnFailedRow() = contract.rollbackOnFailedRow()

    @Test fun cancellationBeforeCommitRollsBack() = contract.cancellationBeforeCommitRollsBack()

    @Test fun readSnapshotIsConsistent() = contract.readSnapshotIsConsistent()
}
