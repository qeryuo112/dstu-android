package com.deepstudent.features.environments.data

import androidx.test.platform.app.InstrumentationRegistry
import android.net.ConnectivityManager
import com.deepstudent.features.environments.api.EnvironmentCatalog
import com.deepstudent.features.environments.api.EnvironmentFailure
import com.deepstudent.features.environments.api.EnvironmentFailureCode
import com.deepstudent.features.environments.api.EnvironmentManager
import com.deepstudent.features.environments.api.EnvironmentPhase
import com.deepstudent.features.environments.api.EnvironmentSnapshot
import com.deepstudent.features.environments.api.PackageRecipeStatus
import com.deepstudent.features.environments.api.RootfsArchiveSource
import com.deepstudent.engines.proot.PackagedPRootArtifact
import com.deepstudent.engines.proot.PRootRuntimeInstaller
import com.deepstudent.engines.proot.PRootProcessRunner
import com.deepstudent.engines.proot.PRootRunRequest
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EnvironmentDeviceTest {
    private lateinit var host: EnvironmentForegroundHost
    private lateinit var manager: EnvironmentManager
    private lateinit var platform: AndroidEnvironmentPlatform
    private lateinit var base: File

    @Before
    fun setUp() {
        host = EnvironmentForegroundHost().also { it.start() }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = InstrumentationRegistry.getArguments().getString("base")?.let(::File)
            ?: File(context.filesDir, "environment-device-${UUID.randomUUID()}")
        base = root
        val artifact = PackagedPRootArtifact.fromNativeDirectory(File(context.applicationInfo.nativeLibraryDir))
        platform = AndroidEnvironmentPlatform(
            baseDirectory = root,
            runtimeDirectory = File(context.codeCacheDir, "environment-device-${root.name}/runtime"),
            executableDirectory = context.applicationInfo.nativeLibraryDir.let(::File),
            dnsServers = context.getSystemService(ConnectivityManager::class.java)
                ?.activeNetwork
                ?.let { network ->
                    context.getSystemService(ConnectivityManager::class.java)
                        ?.getLinkProperties(network)
                }
                ?.dnsServers
                ?.mapNotNull { it.hostAddress }
                .orEmpty(),
            artifact = artifact,
        )
        manager = platform.manager()
    }

    @After
    fun tearDown() {
        platform.close()
        host.close()
    }

    @Test(timeout = 30L * 60L * 1000L)
    fun rootfsAptAndProjectVenvAreObservableAndVersioned(): Unit = runBlocking {
        val snapshot = manager.state.value
        assertEquals(EnvironmentPhase.IDLE, snapshot.phase)
        val source = RootfsArchiveSource.fromFile(fileArg("rootfs"))
        manager.installRootfs(EnvironmentCatalog.UbuntuNobleAarch64, source)
        val ready = awaitRootfsReady()
        assertEquals(ready.failure?.toString(), EnvironmentPhase.READY, ready.phase)
        assertTrue(ready.activeGeneration != null)

        manager.installPackage("git")
        val git = awaitRecipe("git")
        assertEquals(PackageRecipeStatus.INSTALLED, git.status)
        assertTrue(git.versions.isNotEmpty())
        android.util.Log.i("DstuEnvironmentEvidence", "recipe=git versions=${git.versions}")

        manager.installPackage("project-venv")
        val venv = awaitRecipe("project-venv")
        assertEquals(PackageRecipeStatus.INSTALLED, venv.status)
        assertTrue(venv.versions.any { it.name == "requests" && it.version == "2.32.3" })
        android.util.Log.i("DstuEnvironmentEvidence", "recipe=project-venv versions=${venv.versions}")

    }

    @Test
    fun missingArtifactRemainsObservableWithoutStartingProcesses() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val product = AndroidEnvironmentPlatform(
            baseDirectory = File(context.filesDir, "environment-product-${UUID.randomUUID()}"),
        ).manager()
        assertEquals(EnvironmentPhase.IDLE, product.state.value.phase)
        assertTrue(product.state.value.failure == null)
    }

    @Test
    fun nativeLibraryProotStartsInTheAppProcess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val process = ProcessBuilder(
            File(context.applicationInfo.nativeLibraryDir, "libdstu_proot.so").absolutePath,
            "--version",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor())
        assertTrue(output.contains("proot"))
    }

    @Test(timeout = 60_000)
    fun cancellationKillsTheTracerChildrenAndReturnsPipeWorkers(): Unit = runBlocking {
        val generation = requireNotNull(manager.state.value.activeGeneration) { "pass a previously installed test base" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val native = File(context.applicationInfo.nativeLibraryDir)
        val runtime = PRootRuntimeInstaller().prepare(
            PackagedPRootArtifact.fromNativeDirectory(native),
            File(context.codeCacheDir, "proot-cancel-runtime"),
            executableDirectory = native,
        )
        val root = EnvironmentPaths(base).generationRoot(generation)
        val marker = File(root, "root/dstu-cancel-child")
        marker.delete()
        val before = pipeWorkers()
        val operation = async {
            PRootProcessRunner(runtime).run(PRootRunRequest(
                rootfs = root,
                script = "sleep 120 & child=\$!; printf '%s' \"\$child\" > /root/dstu-cancel-child; wait",
            ))
        }
        withTimeout(10_000) { while (!marker.isFile || marker.length() == 0L) delay(50) }
        val child = marker.readText().trim().toInt()
        operation.cancelAndJoin()
        withTimeout(5_000) { while (liveProcess(child)) delay(50) }
        assertEquals(before, pipeWorkers())
        marker.delete()
        android.util.Log.i("DstuEnvironmentEvidence", "event=cancel-terminated workers=$before")
    }

    @Test(timeout = 300_000)
    fun reentryUsesProductionRecipeAndStableVenvPointer(): Unit = runBlocking {
        if (manager.state.value.failure != null) manager.retry() else manager.installPackage("git")
        val git = awaitRecipe("git")
        assertEquals(PackageRecipeStatus.INSTALLED, git.status)
        manager.installPackage("project-venv")
        val venv = awaitRecipe("project-venv")
        assertEquals(PackageRecipeStatus.INSTALLED, venv.status)
        assertTrue(venv.versions.any { it.name == "requests" && it.version == "2.32.3" })
        val generation = requireNotNull(manager.state.value.activeGeneration)
        val pointer = File(EnvironmentPaths(base).generationRoot(generation), "root/projects/dstu/.venv")
        assertTrue(java.nio.file.Files.isSymbolicLink(pointer.toPath()))
        android.util.Log.i("DstuEnvironmentEvidence", "recipe=git versions=${git.versions}")
        android.util.Log.i("DstuEnvironmentEvidence", "recipe=project-venv versions=${venv.versions}")
    }

    private fun liveProcess(pid: Int): Boolean {
        val stat = File("/proc/$pid/stat")
        if (!stat.isFile) return false
        val state = stat.readText().substringAfterLast(") ").substringBefore(' ')
        return state != "Z" && state != "X"
    }

    private fun pipeWorkers(): Int = Thread.getAllStackTraces().keys.count {
        it.name == "dstu-proot-output" && it.isAlive
    }

    private suspend fun awaitRootfsReady(): EnvironmentSnapshot = withTimeout(15L * 60L * 1000L) {
        manager.state.first {
            it.phase == EnvironmentPhase.READY || it.failure != null
        }
    }

    private suspend fun awaitRecipe(id: String) = withTimeout(15L * 60L * 1000L) {
        manager.state.first { snapshot ->
            snapshot.phase in setOf(EnvironmentPhase.READY, EnvironmentPhase.FAILED, EnvironmentPhase.CANCELLED) && snapshot.recipes.firstOrNull { it.id == id }?.let {
                it.status == PackageRecipeStatus.INSTALLED || it.status == PackageRecipeStatus.FAILED
            } ?: false
        }.recipes.first { it.id == id }
    }

    private fun fileArg(name: String): File =
        File(
            InstrumentationRegistry.getArguments().getString(name)
                ?: error("missing instrumentation argument $name"),
        )
}
