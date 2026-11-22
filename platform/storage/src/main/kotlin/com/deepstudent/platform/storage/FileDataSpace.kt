package com.deepstudent.platform.storage

import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import java.io.File
import java.nio.file.Files

/**
 * [filesDir] is the trusted application-private files directory, not the legacy active root.
 * Never retain or use a root after its lease/scope closes. All storage owners must participate.
 */
class FileDataSpace(
    filesDir: File,
    logger: Logger = NoopLogger,
    audit: DataSpaceAudit = DataSpaceAudit.NONE,
    private val phaseHook: DataSpacePhaseHook = DataSpacePhaseHook.NONE,
) {
    private val root: File = canonicalAnchor(filesDir)
    private val logger = DataSpaceLogger(logger, audit)
    private val layout = DataSpaceLayout(root)

    fun acquireActive(): ActiveSlotLease = logger.operation(DataSpacePhase.ACQUIRE) {
        layout.ensureLockParent()
        initializeIfNeeded()
        val lock = DataSpaceLocks.shared(layout.lock)
        try {
            val active = layout.rootFor(layout.current())
            ActiveLease(active, lock, logger)
        } catch (failure: Exception) {
            lock.close()
            throw failure
        }
    }

    fun <T> withMaintenance(block: DataSpaceMaintenance.() -> T): T = logger.operation(DataSpacePhase.MAINTENANCE) {
        layout.ensureLockParent()
        val lock = DataSpaceLocks.maintenance(layout.lock)
        try {
            layout.initialize()
            layout.ensureCommitted(layout.current())
            val scope = MaintenanceScope(layout, logger, phaseHook)
            try { scope.block() } finally { scope.close() }
        } finally {
            lock.close()
        }
    }

    private fun initializeIfNeeded() {
        val read = DataSpaceLocks.shared(layout.lock)
        val ready = try { layout.ready() } finally { read.close() }
        if (ready) return
        val write = DataSpaceLocks.maintenance(layout.lock)
        try { layout.initialize() } finally { write.close() }
    }

    private companion object {
        fun canonicalAnchor(file: File): File {
            if (Files.isSymbolicLink(file.toPath())) throw DataSpaceException(DataSpaceError.UNSAFE_ENTRY)
            return try { file.canonicalFile } catch (_: Exception) { throw DataSpaceException(DataSpaceError.IO_FAILURE) }
        }
    }
}

private class ActiveLease(
    private val active: File,
    private val lock: AutoCloseable,
    private val logger: DataSpaceLogger,
) : ActiveSlotLease {
    private var closed = false
    override val root: File @Synchronized get() {
        if (closed) throw DataSpaceException(DataSpaceError.CLOSED)
        return active
    }

    @Synchronized override fun close() {
        if (!closed) {
            closed = true
            logger.operation(DataSpacePhase.RELEASE) { lock.close() }
        }
    }
}
