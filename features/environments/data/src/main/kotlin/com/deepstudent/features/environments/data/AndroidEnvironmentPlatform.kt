package com.deepstudent.features.environments.data

import com.deepstudent.core.common.time.SystemWallClock
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.engines.proot.PRootArtifact
import com.deepstudent.engines.proot.PRootRuntimeInstaller
import com.deepstudent.engines.proot.PRootRuntimePaths
import com.deepstudent.engines.proot.PRootUnavailableException
import com.deepstudent.features.environments.api.EnvironmentCommandException
import com.deepstudent.features.environments.api.EnvironmentFailure
import com.deepstudent.features.environments.api.EnvironmentFailureCode
import com.deepstudent.features.environments.api.EnvironmentManager
import com.deepstudent.features.environments.api.EnvironmentSnapshot
import com.deepstudent.features.environments.backend.EnvironmentCoordinator
import com.deepstudent.features.environments.backend.EnvironmentJournal
import com.deepstudent.features.environments.backend.EnvironmentPorts
import com.deepstudent.features.environments.backend.EnvironmentService
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Self-contained S1.21 platform composition. The app supplies a private base directory and,
 * when a trusted artifact is available, a PRoot artifact. With no artifact the manager remains
 * observable but all execution fails closed with PROOT_UNAVAILABLE.
 */
class AndroidEnvironmentPlatform(
    baseDirectory: File,
    runtimeDirectory: File = File(baseDirectory, "runtime"),
    executableDirectory: File? = null,
    dnsServers: List<String> = emptyList(),
    artifact: PRootArtifact? = null,
    clock: WallClock = SystemWallClock,
) : AutoCloseable {
    private val paths = EnvironmentPaths(baseDirectory, runtimeDirectory, dnsServers).also {
        it.ensureDirectories()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runtime: PRootRuntimePaths? = prepareRuntime(
        artifact,
        executableDirectory,
    )
    private val journalStore = FileEnvironmentJournalStore(paths.journal)
    private val coordinator = EnvironmentCoordinator(
        EnvironmentPorts(
            journalStore = journalStore,
            archiveStore = FileRootfsArchiveStore(paths),
            extractor = TarXzRootfsExtractor(paths),
            healthChecker = PRootHealthChecker(paths, runtime),
            activator = FileRootfsActivator(paths),
            spaceChecker = AndroidEnvironmentSpaceChecker(baseDirectory),
            packageInstaller = PRootPackageInstaller(paths, runtime, clock),
        ),
        clock,
    )
    private val service = EnvironmentService(coordinator, loadInitialJournal(), scope)

    fun manager(): EnvironmentManager = service

    override fun close() {
        scope.cancel()
        EnvironmentDataLog.event("environment-owner-closed")
    }

    private fun prepareRuntime(
        artifact: PRootArtifact?,
        executableDirectory: File?,
    ): PRootRuntimePaths? {
        if (artifact == null) return null
        return try {
            PRootRuntimeInstaller().prepare(
                artifact = artifact,
                targetDirectory = File(paths.runtime, "bin"),
                executableDirectory = executableDirectory,
            )
        } catch (failure: PRootUnavailableException) {
            EnvironmentDataLog.failure("proot-unavailable", failure)
            null
        } catch (failure: Exception) {
            EnvironmentDataLog.failure("proot-prepare-failed", failure)
            null
        }
    }

    private fun loadInitialJournal(): EnvironmentJournal? = try {
        journalStore.load()
    } catch (failure: EnvironmentCommandException) {
        EnvironmentDataLog.failure("journal-corrupt", failure)
        EnvironmentJournal(
            identity = com.deepstudent.features.environments.backend.EnvironmentJournalIdentity(),
            failure = failure.failure,
            updatedAt = Instant.now(),
        )
    }
}

internal class AndroidEnvironmentSpaceChecker(private val baseDirectory: File) : com.deepstudent.features.environments.backend.EnvironmentSpaceChecker {
    override fun availableBytes(): Long = android.os.StatFs(baseDirectory.absolutePath).availableBytes
}

fun EnvironmentSnapshot.isReady(): Boolean =
    phase == com.deepstudent.features.environments.api.EnvironmentPhase.READY &&
        activeGeneration != null
