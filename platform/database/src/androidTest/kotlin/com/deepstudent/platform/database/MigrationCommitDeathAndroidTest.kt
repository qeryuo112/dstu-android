package com.deepstudent.platform.database

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.platform.database.internal.MigrationCommitKind
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationCommitDeathAndroidTest {
    @Test fun checkpoint() {
        val args = InstrumentationRegistry.getArguments()
        val run = args.getString("s203Run")
        assumeTrue("host must orchestrate process deaths with verify-migration-death.sh", run != null)
        require(run != null && run.matches(Regex("s203-death-[0-9]+-[0-9]+")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val database = SharedDatabase.entries.single { it.id == args.getString("s203Database") }
        val desktop = args.getString("s203Origin") == "desktop"
        require(!desktop || database == SharedDatabase.VFS)
        val root = File(context.cacheDir, "$run/${if (desktop) "desktop-vfs" else database.id}")
        require(root.isDirectory)
        val golden = if (desktop) instrumentation.context.assets.open("config/baseline/schema-upgrade.json").use {
            it.readBytes()
        } else null
        val contract = MigrationCommitDeathContract(root, database, BundledMigrationSource(context), golden, Process.myPid().toLong())
        when (args.getString("s203Operation")) {
            "advance" -> contract.advance(checkNotNull(args.getString("s203Before")).toInt(), ::killThisProcess)
            "governance" -> contract.migrateLegacyHead(::killThisProcess)
            "verify" -> contract.verifyHead(MigrationCommitKind.valueOf(checkNotNull(args.getString("s203Kind"))))
            else -> error("unknown checkpoint operation")
        }
    }

    private fun killThisProcess(): Nothing {
        Process.killProcess(Process.myPid()) // SIGKILL on the process that owns the still-open SQLite connection.
        error("process survived SIGKILL")
    }
}
