package com.deepstudent.app.execution

import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.DstuApplication
import com.deepstudent.app.editor.EditorTestHost
import com.deepstudent.features.environments.api.EnvironmentCatalog
import com.deepstudent.features.environments.api.EnvironmentPhase
import com.deepstudent.features.environments.api.RootfsArchiveSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Provisions only the isolated test package through the same manager as the workbench. */
@RunWith(AndroidJUnit4::class)
class ShellDevicePreparationTest {
    @Test fun installFrozenLinuxEnvironment(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as DstuApplication
        check(app.packageName == "com.deepstudent.app.s2device") { "isolated test APK required" }
        val archive = File(app.cacheDir, "s227-frozen-rootfs.tar.xz")
        val catalog = EnvironmentCatalog.UbuntuNobleAarch64
        check(archive.isFile && archive.length() == catalog.archiveBytes) { "frozen fixture not staged" }
        val host = EditorTestHost()
        try {
            host.start()
            instrumentation.runOnMainSync { host.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            withContext(Dispatchers.IO) {
                val manager = app.environments()
                assertEquals(EnvironmentPhase.IDLE, manager.state.value.phase)
                manager.installRootfs(catalog, RootfsArchiveSource.fromFile(archive))
                val terminal = withTimeout(10 * 60_000L) {
                    manager.state.first { it.phase == EnvironmentPhase.READY || it.failure != null }
                }
                assertEquals(terminal.failure?.toString(), EnvironmentPhase.READY, terminal.phase)
                assertEquals(catalog.sha256, terminal.rootfsSha256)
                assertNotNull(terminal.activeGeneration)
                println("S227_LINUX_PROVISION phase=READY archive_sha256=${catalog.sha256} app_uid=${android.os.Process.myUid()}")
            }
        } finally {
            host.close()
            archive.delete()
        }
    }
}
