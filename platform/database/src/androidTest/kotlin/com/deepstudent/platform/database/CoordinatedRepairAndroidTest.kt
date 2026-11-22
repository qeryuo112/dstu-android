package com.deepstudent.platform.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoordinatedRepairAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "s2-04-repair-${System.nanoTime()}").apply { mkdirs() }
    private val contract = CoordinatedRepairContract(root, BundledMigrationSource(context))

    @After fun cleanup() { root.deleteRecursively() }
    @Test fun freshAndRestartApplyRepair() = contract.freshAndRestartApplyRepair()
    @Test fun oldHeadUpgradePreservesRowsAndLogThenDelete() = contract.oldHeadUpgradePreservesRowsAndLogThenDelete()
    @Test fun rejectsUntrustedInputsWithoutOpening() = contract.rejectsUntrustedInputsWithoutOpening()
    @Test fun rejectsOldHeadDriftWithoutChangingData() = contract.rejectsOldHeadDriftWithoutChangingData()
}
