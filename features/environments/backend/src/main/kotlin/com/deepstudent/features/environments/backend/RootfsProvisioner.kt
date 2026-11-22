package com.deepstudent.features.environments.backend

import com.deepstudent.features.environments.api.EnvironmentCommandException
import com.deepstudent.features.environments.api.EnvironmentFailure
import com.deepstudent.features.environments.api.EnvironmentFailureCode
import com.deepstudent.features.environments.api.EnvironmentPhase
import com.deepstudent.features.environments.api.EnvironmentProgress
import com.deepstudent.features.environments.api.EnvironmentStage
import com.deepstudent.features.environments.api.RootfsArchiveSource
import com.deepstudent.features.environments.api.RootfsManifest
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Coordinates ports only; no archive, filesystem or process implementation lives here. */
internal class RootfsProvisioner(private val ports: EnvironmentPorts) {
    suspend fun run(tx: EnvironmentTransaction, manifest: RootfsManifest, source: RootfsArchiveSource?) {
        while (tx.journal.checkpoint != EnvironmentCheckpoint.DONE) {
            coroutineContext.ensureActive()
            when (tx.journal.checkpoint) {
                EnvironmentCheckpoint.VERIFY -> verify(tx, manifest, source)
                EnvironmentCheckpoint.EXTRACT -> extract(tx, manifest)
                EnvironmentCheckpoint.HEALTH -> health(tx, manifest)
                EnvironmentCheckpoint.ACTIVATE -> activate(tx, manifest)
                EnvironmentCheckpoint.DONE -> Unit
            }
        }
        val active = ports.activator.currentActive(manifest)
        requireState(active != null && active == tx.journal.activeGeneration)
        tx.progress(EnvironmentPhase.READY, EnvironmentProgress(stage = EnvironmentStage.COMPLETE))
        EnvironmentLog.event("rootfs-ready", "task=${tx.journal.lastTaskId} generation=$active")
    }

    private suspend fun verify(tx: EnvironmentTransaction, manifest: RootfsManifest, source: RootfsArchiveSource?) {
        EnvironmentPolicy.requireSpace(ports.spaceChecker, manifest.minimumFreeBytes)
        tx.progress(EnvironmentPhase.VERIFYING, EnvironmentProgress(stage = EnvironmentStage.ARCHIVE_VERIFY))
        val token = ports.archiveStore.ensureVerified(manifest, source) {
            tx.progress(EnvironmentPhase.VERIFYING, it)
        }
        requireState(token == manifest.sha256)
        tx.checkpoint(EnvironmentCheckpoint.EXTRACT)
    }

    private suspend fun extract(tx: EnvironmentTransaction, manifest: RootfsManifest) {
        // The verified archive already occupies disk; reserve only remaining extraction + safety headroom.
        EnvironmentPolicy.requireSpace(ports.spaceChecker, manifest.minimumFreeBytes - manifest.archiveBytes)
        tx.progress(EnvironmentPhase.EXTRACTING, EnvironmentPolicy.initialExtraction(manifest))
        val generation = requireNotNull(tx.journal.generationId)
        val staged = ports.extractor.extract(manifest, manifest.sha256, generation) {
            tx.progress(EnvironmentPhase.EXTRACTING, it)
        }
        requireState(staged == generation)
        tx.checkpoint(EnvironmentCheckpoint.HEALTH)
    }

    private suspend fun health(tx: EnvironmentTransaction, manifest: RootfsManifest) {
        tx.progress(EnvironmentPhase.HEALTH_CHECKING, EnvironmentProgress(stage = EnvironmentStage.HEALTH_CHECK))
        val observation = ports.healthChecker.check(manifest, requireNotNull(tx.journal.generationId))
        EnvironmentPolicy.requireHealthy(manifest, observation)
        tx.checkpoint(EnvironmentCheckpoint.ACTIVATE)
    }

    private suspend fun activate(tx: EnvironmentTransaction, manifest: RootfsManifest) {
        tx.progress(EnvironmentPhase.ACTIVATING, EnvironmentProgress(stage = EnvironmentStage.ACTIVATE))
        val generation = requireNotNull(tx.journal.generationId)
        // Reconcile pointer-before-journal crashes without extracting or running health again.
        val current = ports.activator.currentActive(manifest)
        val active = if (current == generation) current else ports.activator.activate(manifest, generation)
        requireState(active == generation)
        requireState(ports.activator.currentActive(manifest) == active)
        tx.commit(tx.journal.copy(activeGeneration = active, receipts = emptyMap()))
        tx.checkpoint(EnvironmentCheckpoint.DONE)
    }

    private fun requireState(valid: Boolean) {
        if (valid) return
        throw EnvironmentCommandException(EnvironmentFailure(
            EnvironmentFailureCode.CORRUPT_STATE, "rootfs identity or active pointer did not match the journal", false,
        ))
    }
}
