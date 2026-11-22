package com.deepstudent.services.sync.data.capture

import com.deepstudent.platform.database.MigrationSource
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Test

class SyncCaptureContractTest {
    private val root = Files.createTempDirectory("sync-capture-").toFile()
    private val repository = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "config/schema/final-lock.json").isFile }
    private val source = MigrationSource { repository.resolve(it).readBytes() }
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
