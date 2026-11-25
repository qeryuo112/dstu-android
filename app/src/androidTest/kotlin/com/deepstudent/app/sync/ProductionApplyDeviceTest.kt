package com.deepstudent.app.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.backup.BackupForegroundRule
import com.deepstudent.app.storage.AndroidOperationLogger
import com.deepstudent.platform.database.BundledMigrationSource
import com.deepstudent.platform.security.AndroidKeystoreKeyWrapper
import com.deepstudent.platform.security.SecurityFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProductionApplyDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "fixture.s230.${UUID.randomUUID()}"

    @get:Rule val foreground = BackupForegroundRule()

    @get:Rule val temp = TemporaryFolder(context.cacheDir.canonicalFile)

    @After fun removeFixtureKey() = AndroidKeystoreKeyWrapper(alias).deleteKey()

    @Test fun migratedDomainRowsRoundtripThroughProductionFactoryWithNoUploadEcho() = runBlocking { cases().roundtrip(this) }

    @Test fun lastOwnerFailureKeepsCursorAndReplayDoesNotRepeatCommittedRows() = runBlocking { cases().replay(this) }

    private fun cases() = ProductionApplyCases(BundledMigrationSource(context), temp.root, AndroidOperationLogger) { root ->
        SecurityFactory.create(root, alias)
    }
}
