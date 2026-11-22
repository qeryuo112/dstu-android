package com.deepstudent.services.backup.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.platform.database.BundledMigrationSource
import java.io.File
import java.util.UUID
import org.junit.Rule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupIdentityAndroidTest : BackupIdentityContractTest() {
    @get:Rule val foreground = BackupIdentityForegroundRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    override val root: File = File(context.cacheDir, "backup-identity-${UUID.randomUUID()}").apply { check(mkdir()) }
    override val migrations = BundledMigrationSource(context)
    override fun fixture(name: String) = InstrumentationRegistry.getInstrumentation().context.assets
        .open("fixtures/legacy-mobile/$name").use { it.readBytes() }
}
