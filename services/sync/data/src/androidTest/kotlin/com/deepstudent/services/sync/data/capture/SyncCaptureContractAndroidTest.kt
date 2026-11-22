package com.deepstudent.services.sync.data.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.platform.database.BundledMigrationSource
import java.io.File
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncCaptureContractAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "sync-capture-${System.nanoTime()}").apply { check(mkdirs()) }
    private val source = BundledMigrationSource(context)
    private val contract by lazy { SyncCaptureContract(root, source) }
    private val repair by lazy { SyncCaptureRepairContract(root, source) }

    @After fun cleanup() { root.deleteRecursively() }
    @Test fun frozenRegistryAndAllFourRealSchemasAgree() = contract.frozenRegistryAndAllFourRealSchemasAgree()
    @Test fun effectiveSchemaVersionsAreDefensiveAndUnmodifiable() = contract.effectiveSchemaVersionsAreDefensiveAndUnmodifiable()
    @Test fun rowSyncCrudAndLocalCrudAreIsolatedInRealDatabases() = contract.rowSyncCrudAndLocalCrudAreIsolatedInRealDatabases()
    @Test fun coordinatedEntitiesPreserveObservedCaptureSemantics() = contract.coordinatedEntitiesPreserveObservedCaptureSemantics()
    @Test fun pluginCrudRecordsTheSameLogicalKeyThroughTombstoneAndDelete() = repair.pluginCrudRecordsTheSameLogicalKeyThroughTombstoneAndDelete()
    @Test fun repairedTriggerHashMatchesStoredSqlAndOldFrozenTriggerDrifts() = repair.repairedTriggerHashMatchesStoredSqlAndOldFrozenTriggerDrifts()
    @Test fun injectedBadDeleteIsDiagnosedWithoutLosingRowOrLog() = repair.injectedBadDeleteIsDiagnosedWithoutLosingRowOrLog()
    @Test fun missingAndAlteredCaptureAreLocatedWithoutValues() = contract.missingAndAlteredCaptureAreLocatedWithoutValues()
    @Test fun extraCaptureAndCompetingTriggerAreRejectedWithoutLeakingUnknownName() = contract.extraCaptureAndCompetingTriggerAreRejectedWithoutLeakingUnknownName()
    @Test fun changedChangeLogDefinitionFailsBeforeSynchronization() = contract.changedChangeLogDefinitionFailsBeforeSynchronization()
    @Test fun unknownTriggerOnChangeLogFailsWithoutLeakingName() = contract.unknownTriggerOnChangeLogFailsWithoutLeakingName()
    @Test fun wrongCompositePrimaryKeyIsLocated() = contract.wrongCompositePrimaryKeyIsLocated()
    @Test fun scopeAllowsOnlyRegisteredConsumption() = contract.scopeAllowsOnlyRegisteredConsumption()
    @Test fun unexpectedReadFailureBlocksEveryConsumptionScope() = contract.unexpectedReadFailureBlocksEveryConsumptionScope()
    @Test fun sourceBytesAreCheckedWithoutLeakingSuppliedDetails() = contract.sourceBytesAreCheckedWithoutLeakingSuppliedDetails()
    @Test fun callerCannotMisidentifyARealSharedDatabase() = contract.callerCannotMisidentifyARealSharedDatabase()
}
