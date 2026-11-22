package com.deepstudent.features.environments.backend

import com.deepstudent.core.common.time.SystemWallClock
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.features.environments.api.EnvironmentCatalog
import com.deepstudent.features.environments.api.EnvironmentCommandException
import com.deepstudent.features.environments.api.EnvironmentFailure
import com.deepstudent.features.environments.api.EnvironmentFailureCode
import com.deepstudent.features.environments.api.EnvironmentPhase
import com.deepstudent.features.environments.api.EnvironmentProgress
import com.deepstudent.features.environments.api.EnvironmentTaskId
import com.deepstudent.features.environments.api.RootfsArchiveSource
import com.deepstudent.features.environments.api.RootfsManifest

typealias EnvironmentUpdate = (
    journal: EnvironmentJournal,
    phase: EnvironmentPhase,
    progress: EnvironmentProgress,
) -> Unit

class EnvironmentCoordinator(
    private val ports: EnvironmentPorts,
    private val clock: WallClock = SystemWallClock,
) {
    suspend fun installRootfs(
        taskId: EnvironmentTaskId,
        manifest: RootfsManifest,
        source: RootfsArchiveSource,
        update: EnvironmentUpdate,
    ): EnvironmentJournal {
        val previous = ports.journalStore.load()
        val journal = EnvironmentJournal(
            identity = EnvironmentJournalIdentity(manifest.id, manifest.sha256, "g-${taskId.value}"),
            activeGeneration = previous?.activeGeneration,
            receipts = previous?.receipts.orEmpty(),
            lastTaskId = taskId.value,
            updatedAt = clock.now(),
        )
        return provision(journal, manifest, source, update)
    }

    suspend fun retry(
        taskId: EnvironmentTaskId,
        manifest: RootfsManifest?,
        source: RootfsArchiveSource?,
        update: EnvironmentUpdate,
    ): EnvironmentJournal {
        val previous = ports.journalStore.load() ?: reject(EnvironmentFailureCode.ARCHIVE_MISSING)
        if (previous.failure?.recoverable == false) reject(EnvironmentFailureCode.CORRUPT_STATE)
        if (previous.pendingRecipeId != null) return installRecipe(taskId, previous.pendingRecipeId, update)
        val resolved = manifest ?: previous.rootfsId?.let(EnvironmentCatalog::find)
            ?: reject(EnvironmentFailureCode.CORRUPT_STATE)
        requireIdentity(previous, resolved)
        return provision(previous.copy(lastTaskId = taskId.value, failure = null), resolved, source, update)
    }

    suspend fun installRecipe(
        taskId: EnvironmentTaskId,
        recipeId: String,
        update: EnvironmentUpdate,
    ): EnvironmentJournal {
        val current = ports.journalStore.load() ?: reject(EnvironmentFailureCode.CORRUPT_STATE)
        // A failed replacement must be resumed before mutating its previous active generation.
        if (current.checkpoint != EnvironmentCheckpoint.DONE) reject(EnvironmentFailureCode.CORRUPT_STATE)
        val recipe = PackageRecipeCatalog.all.firstOrNull { it.id == recipeId }
            ?: reject(EnvironmentFailureCode.PACKAGE_INSTALL_FAILED)
        val initial = current.copy(pendingRecipeId = recipeId, lastTaskId = taskId.value, failure = null)
        val tx = transaction(initial, update)
        return tx.run { PackageInstallation(ports).run(tx, recipe) }
    }

    private suspend fun provision(
        journal: EnvironmentJournal,
        manifest: RootfsManifest,
        source: RootfsArchiveSource?,
        update: EnvironmentUpdate,
    ): EnvironmentJournal {
        requireIdentity(journal, manifest)
        val tx = transaction(journal, update)
        return tx.run { RootfsProvisioner(ports).run(tx, manifest, source) }
    }

    private fun transaction(journal: EnvironmentJournal, update: EnvironmentUpdate): EnvironmentTransaction =
        EnvironmentTransaction(ports.journalStore, clock, journal, update).also { it.commit(journal) }

    private fun requireIdentity(journal: EnvironmentJournal, manifest: RootfsManifest) {
        val identityMatches = journal.rootfsId == manifest.id && journal.rootfsSha256 == manifest.sha256
        val generationSafe = journal.generationId?.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")) == true
        if (!identityMatches || !generationSafe) reject(EnvironmentFailureCode.CORRUPT_STATE)
    }

    private fun reject(code: EnvironmentFailureCode): Nothing = throw EnvironmentCommandException(
        EnvironmentFailure(code, EnvironmentFailureMessages.forCode(code), recoverable = code != EnvironmentFailureCode.CORRUPT_STATE),
    )
}
