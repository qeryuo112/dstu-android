package com.deepstudent.platform.database

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Test

class CoordinatedRepairTest {
    private val root = Files.createTempDirectory("s2-04-repair-jvm").toFile()
    private val repository = File(requireNotNull(System.getProperty("user.dir"))).let {
        if (it.resolve("config/schema/final-lock.json").isFile) it else it.resolve("../..")
    }
    private val source = MigrationSource { repository.resolve(it).readBytes() }
    private val contract = CoordinatedRepairContract(root, source)

    @After fun cleanup() { root.deleteRecursively() }
    @Test fun freshAndRestartApplyRepair() = contract.freshAndRestartApplyRepair()
    @Test fun oldHeadUpgradePreservesRowsAndLogThenDelete() = contract.oldHeadUpgradePreservesRowsAndLogThenDelete()
    @Test fun rejectsUntrustedInputsWithoutOpening() = contract.rejectsUntrustedInputsWithoutOpening()
    @Test fun rejectsOldHeadDriftWithoutChangingData() = contract.rejectsOldHeadDriftWithoutChangingData()
}
