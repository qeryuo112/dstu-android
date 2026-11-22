package com.deepstudent.features.environments.backend

import com.deepstudent.features.environments.api.EnvironmentProgress
import com.deepstudent.features.environments.api.EnvironmentStage
import com.deepstudent.features.environments.api.PackageReceipt
import com.deepstudent.features.environments.api.RootfsArchiveSource
import com.deepstudent.features.environments.api.RootfsManifest

data class RootfsHealthObservation(
    val osId: String,
    val osVersion: String,
    val hasBash: Boolean,
    val hasApt: Boolean,
    val hasPython: Boolean,
)

/** Owns supplied-source import and verification, not source selection/HTTP. Returns manifest.sha256 after durable verification. */
interface RootfsArchiveStore {
    suspend fun ensureVerified(
        manifest: RootfsManifest,
        source: RootfsArchiveSource?,
        onProgress: (EnvironmentProgress) -> Unit,
    ): String
}

/** Owns bounded/safe extraction. Returns the requested generationId only when its marker is durable. */
interface RootfsExtractor {
    suspend fun extract(
        manifest: RootfsManifest,
        archiveToken: String,
        generationId: String,
        onProgress: (EnvironmentProgress) -> Unit,
    ): String
}

interface RootfsHealthChecker {
    suspend fun check(manifest: RootfsManifest, stagingToken: String): RootfsHealthObservation
}

/** Must be idempotent across generation rename and active-pointer commit. Never deletes the old active rootfs. */
interface RootfsActivator {
    suspend fun activate(manifest: RootfsManifest, stagingToken: String): String

    suspend fun currentActive(manifest: RootfsManifest): String?
}

fun interface EnvironmentSpaceChecker {
    fun availableBytes(): Long
}

interface EnvironmentJournalStore {
    fun load(): EnvironmentJournal?

    fun save(journal: EnvironmentJournal)
}

/** Adapter owns commands and a fresh version probe; no receipt until every requested package is measured. */
interface EnvironmentPackageInstaller {
    suspend fun install(
        recipe: PackageRecipe,
        activeGeneration: String,
        onStage: (EnvironmentStage, String) -> Unit,
    ): PackageReceipt
}

data class EnvironmentPorts(
    val journalStore: EnvironmentJournalStore,
    val archiveStore: RootfsArchiveStore,
    val extractor: RootfsExtractor,
    val healthChecker: RootfsHealthChecker,
    val activator: RootfsActivator,
    val spaceChecker: EnvironmentSpaceChecker,
    val packageInstaller: EnvironmentPackageInstaller,
)
