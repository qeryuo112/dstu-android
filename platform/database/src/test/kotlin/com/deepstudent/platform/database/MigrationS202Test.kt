package com.deepstudent.platform.database

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Test

class MigrationS202Test {
    private val root = Files.createTempDirectory("s2-02-jvm").toFile()
    private val repository = File(System.getProperty("user.dir")).let {
        if (it.resolve("config/schema/final-lock.json").isFile) it else it.resolve("../..")
    }
    private val source = MigrationSource { repository.resolve(it).readBytes() }
    private val contract = MigrationS202Contract(root, source, source.read("config/baseline/schema-upgrade.json"))

    @After fun cleanup() { root.deleteRecursively() }
    @Test fun freshAndRestartPreserveGovernanceAndHistory() = contract.freshAndRestartPreserveGovernanceAndHistory()
    @Test fun vfs20261111UpgradeMatchesFrozenFixture() = contract.vfs20261111UpgradeMatchesFrozenFixture()
    @Test fun driftPreservesExistingDatabaseAndLocatesObjects() = contract.driftPreservesExistingDatabaseAndLocatesObjects()
    @Test fun untrustedHistoryAndOrphanManagedObjectsAreRejected() = contract.untrustedHistoryAndOrphanManagedObjectsAreRejected()
    @Test fun migrationAndLedgerRollbackTogether() = contract.migrationAndLedgerRollbackTogether()
    @Test fun foreignKeyRebuildIsAtomic() = contract.foreignKeyRebuildIsAtomic()
}
