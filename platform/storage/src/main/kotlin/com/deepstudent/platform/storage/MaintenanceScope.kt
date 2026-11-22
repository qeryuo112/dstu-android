package com.deepstudent.platform.storage

import java.io.File

internal class MaintenanceScope(
    private val layout: DataSpaceLayout,
    private val logger: DataSpaceLogger,
    private val hook: DataSpacePhaseHook,
) : DataSpaceMaintenance {
    private var open = true
    private val handles = mutableMapOf<String, Stage>()

    override val active: File get() {
        checkOpen()
        return layout.rootFor(layout.current())
    }

    override fun prepare(operationId: String): DataSpaceStaging = logger.operation(DataSpacePhase.PREPARE) { trace ->
        checkOpen()
        val slot = DataSpaceState.slotFor(operationId)
        layout.prepare(slot, layout.current())
        val stage = handles.getOrPut(slot) { Stage(this, slot) }
        checkpoint(trace, DataSpacePhase.STAGING_PREPARED)
        stage
    }

    override fun activate(staging: DataSpaceStaging, validate: (File) -> Unit): ActivationResult =
        logger.operation(DataSpacePhase.ACTIVATE) { trace ->
            val stage = own(staging)
            when (status(stage)) {
                StagingStatus.ACTIVE -> ActivationResult.ALREADY_ACTIVE
                StagingStatus.PREVIOUSLY_ACTIVATED -> ActivationResult.PREVIOUSLY_ACTIVATED
                StagingStatus.WRITABLE -> activateNew(stage, validate, trace)
            }
        }

    private fun activateNew(stage: Stage, validate: (File) -> Unit, trace: DataSpaceLogger.Trace): ActivationResult {
        val data = stage.root
        DurableFiles.inspectTree(data)
        try { validate(data) } catch (_: Exception) { throw DataSpaceException(DataSpaceError.VALIDATION_FAILED) }
        checkpoint(trace, DataSpacePhase.STAGING_VALIDATED)
        layout.syncStaging(stage.slot)
        checkpoint(trace, DataSpacePhase.STAGING_DURABLE)
        layout.ensureCommitted(layout.current())
        layout.switchPointer(stage.slot) { checkpoint(trace, it) }
        layout.ensureCommitted(stage.slot)
        checkpoint(trace, DataSpacePhase.COMMIT_RECORDED)
        return ActivationResult.ACTIVATED
    }

    private fun checkpoint(trace: DataSpaceLogger.Trace, phase: DataSpacePhase) {
        trace.checkpoint(phase)
        hook.onPhase(phase)
    }

    override fun discard(staging: DataSpaceStaging) = logger.operation(DataSpacePhase.DISCARD) {
        val stage = own(staging)
        if (!stage.discarded) {
            if (status(stage) != StagingStatus.WRITABLE) throw DataSpaceException(DataSpaceError.FOREIGN_STAGING)
            layout.discard(stage.slot)
            stage.discarded = true
            handles.remove(stage.slot)
        }
    }

    fun status(stage: Stage): StagingStatus {
        checkOpen()
        if (stage.discarded) throw DataSpaceException(DataSpaceError.CLOSED)
        return layout.status(stage.slot, layout.current())
    }

    fun stageRoot(stage: Stage): File {
        if (status(stage) != StagingStatus.WRITABLE) throw DataSpaceException(DataSpaceError.CLOSED)
        return layout.rootFor(stage.slot)
    }

    fun close() { open = false }
    private fun own(staging: DataSpaceStaging): Stage {
        checkOpen()
        val stage = staging as? Stage ?: throw DataSpaceException(DataSpaceError.FOREIGN_STAGING)
        if (stage.owner !== this) throw DataSpaceException(DataSpaceError.FOREIGN_STAGING)
        return stage
    }

    private fun checkOpen() { if (!open) throw DataSpaceException(DataSpaceError.CLOSED) }
}

internal class Stage(val owner: MaintenanceScope, val slot: String) : DataSpaceStaging {
    var discarded = false
    override val status: StagingStatus get() = owner.status(this)
    override val root: File get() = owner.stageRoot(this)
}
