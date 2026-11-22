package com.deepstudent.features.environments.backend

import com.deepstudent.core.common.time.WallClock
import com.deepstudent.features.environments.api.EnvironmentCatalog
import com.deepstudent.features.environments.api.EnvironmentPhase
import com.deepstudent.features.environments.api.EnvironmentProgress
import com.deepstudent.features.environments.api.EnvironmentStage
import com.deepstudent.features.environments.api.InstalledVersion
import com.deepstudent.features.environments.api.PackageReceipt
import com.deepstudent.features.environments.api.RootfsArchiveSource
import com.deepstudent.features.environments.api.RootfsManifest
import java.io.IOException
import java.time.Instant

internal val testTime: Instant = Instant.parse("2026-11-17T08:00:00Z")
internal val testManifest: RootfsManifest = EnvironmentCatalog.UbuntuNobleAarch64
internal val unreadSource = RootfsArchiveSource { error("backend must never open a source") }

internal fun gitReceipt() = PackageReceipt("git", testTime.toString(), listOf(InstalledVersion("git", "2.43.0", "apt")))
internal fun readyJournal(generation: String = "generation-old") = EnvironmentJournal(
    identity = EnvironmentJournalIdentity(testManifest.id, testManifest.sha256, generation),
    checkpoint = EnvironmentCheckpoint.DONE,
    activeGeneration = generation,
    receipts = mapOf("git" to gitReceipt()),
    updatedAt = testTime,
)

internal class TestJournalStore(var value: EnvironmentJournal? = null) : EnvironmentJournalStore {
    val writes = mutableListOf<EnvironmentJournal>()
    var reject: (EnvironmentJournal) -> Boolean = { false }
    override fun load(): EnvironmentJournal? = value
    override fun save(journal: EnvironmentJournal) {
        if (reject(journal)) throw IOException("private-file-location-not-for-logs")
        value = journal
        writes += journal
    }
}

internal class EnvironmentFixture(initial: EnvironmentJournal? = null) {
    val store = TestJournalStore(initial)
    val counts = mutableMapOf<EnvironmentCheckpoint, Int>()
    var available = Long.MAX_VALUE
    var active: String? = initial?.activeGeneration
    var archiveToken = testManifest.sha256
    var wrongStaging = false
    var healthy = true
    var receipt = gitReceipt()
    var packageCalls = 0
    var packageEffect: suspend () -> Unit = {}
    var effect: suspend (EnvironmentCheckpoint) -> Unit = {}
    val generations = mutableListOf<String>()
    val updates = mutableListOf<Pair<EnvironmentJournal, EnvironmentPhase>>()
    val update: EnvironmentUpdate = { journal, phase, _ -> updates += journal to phase }

    private suspend fun step(checkpoint: EnvironmentCheckpoint) {
        check(store.value?.checkpoint == checkpoint) { "intent must be durable before effect" }
        counts[checkpoint] = count(checkpoint) + 1
        effect(checkpoint)
    }
    fun count(checkpoint: EnvironmentCheckpoint) = counts[checkpoint] ?: 0

    val ports = EnvironmentPorts(
        journalStore = store,
        archiveStore = object : RootfsArchiveStore {
            override suspend fun ensureVerified(manifest: RootfsManifest, source: RootfsArchiveSource?, onProgress: (EnvironmentProgress) -> Unit): String {
                step(EnvironmentCheckpoint.VERIFY)
                onProgress(EnvironmentProgress(EnvironmentStage.ARCHIVE_COPY, processedBytes = manifest.archiveBytes))
                return archiveToken
            }
        },
        extractor = object : RootfsExtractor {
            override suspend fun extract(manifest: RootfsManifest, archiveToken: String, generationId: String, onProgress: (EnvironmentProgress) -> Unit): String {
                check(archiveToken == manifest.sha256)
                generations += generationId
                step(EnvironmentCheckpoint.EXTRACT)
                return if (wrongStaging) "wrong-generation" else generationId
            }
        },
        healthChecker = object : RootfsHealthChecker {
            override suspend fun check(manifest: RootfsManifest, stagingToken: String): RootfsHealthObservation {
                check(stagingToken == store.value?.generationId)
                step(EnvironmentCheckpoint.HEALTH)
                return RootfsHealthObservation(manifest.osId, manifest.osVersion, true, healthy, true)
            }
        },
        activator = object : RootfsActivator {
            override suspend fun activate(manifest: RootfsManifest, stagingToken: String): String {
                step(EnvironmentCheckpoint.ACTIVATE)
                active = stagingToken
                return stagingToken
            }
            override suspend fun currentActive(manifest: RootfsManifest): String? = active
        },
        spaceChecker = EnvironmentSpaceChecker { available },
        packageInstaller = object : EnvironmentPackageInstaller {
            override suspend fun install(recipe: PackageRecipe, activeGeneration: String, onStage: (EnvironmentStage, String) -> Unit): PackageReceipt {
                check(store.value?.pendingRecipeId == recipe.id) { "package intent must be durable" }
                packageCalls++
                onStage(EnvironmentStage.PROBE, "not exposed in state or logs")
                packageEffect()
                return receipt
            }
        },
    )
    fun coordinator() = EnvironmentCoordinator(ports, WallClock { testTime })
}
