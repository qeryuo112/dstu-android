package com.deepstudent.platform.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationS202AndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val root = File(context.cacheDir, "s2-02-migrations-${System.nanoTime()}").apply { mkdirs() }
    private val source = BundledMigrationSource(context)
    private val fixture = instrumentation.context.assets.open("config/baseline/schema-upgrade.json").use { it.readBytes() }
    private val contract = MigrationS202Contract(root, source, fixture)

    @After fun cleanup() { root.deleteRecursively() }
    @Test fun freshAndRestartPreserveGovernanceAndHistory() = contract.freshAndRestartPreserveGovernanceAndHistory()
    @Test fun vfs20261111UpgradeMatchesFrozenFixture() = contract.vfs20261111UpgradeMatchesFrozenFixture()
    @Test fun driftPreservesExistingDatabaseAndLocatesObjects() = contract.driftPreservesExistingDatabaseAndLocatesObjects()
    @Test fun untrustedHistoryAndOrphanManagedObjectsAreRejected() = contract.untrustedHistoryAndOrphanManagedObjectsAreRejected()
    @Test fun migrationAndLedgerRollbackTogether() = contract.migrationAndLedgerRollbackTogether()
    @Test fun foreignKeyRebuildIsAtomic() = contract.foreignKeyRebuildIsAtomic()
}
