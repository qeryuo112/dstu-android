package com.deepstudent.app.backup

import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.platform.database.BundledMigrationSource
import org.junit.rules.TemporaryFolder
import java.io.File

internal fun remediationTemporaryFolder() = TemporaryFolder(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)

internal fun androidRestoreFixture(root: File): RestoreSqlFixture {
    val runner = InstrumentationRegistry.getInstrumentation()
    return RestoreSqlFixture(root, BundledMigrationSource(runner.targetContext)) {
        runner.context.assets.open("desktop-v3-full.json").bufferedReader().use { it.readText() }
    }
}

internal fun remediationCorpus() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve("s2-remediation")
