package com.deepstudent.services.resources.data

import java.nio.file.Path

/** Test-only injection surface; production uses real space and no fault injection. */
internal enum class ResourceCut {
    PREPARE_READ, PREPARE_WRITE, BEFORE_PUBLISH, AFTER_MARKER, AFTER_MOVE, AFTER_PUBLISH, BEFORE_REAP_MOVE, AFTER_REAP_MOVE, BEFORE_DATABASE, BEFORE_COMMIT, BEFORE_SETTLE, AFTER_COMMIT_CLEANUP, AFTER_COMMIT_CLOSE,
}
internal fun interface ResourceFaults {
    fun hit(cut: ResourceCut, bytes: Long)
    companion object { val NONE = ResourceFaults { _, _ -> } }
}
internal fun interface AvailableSpace {
    fun bytes(path: Path): Long
    companion object { val REAL = AvailableSpace { it.toFile().usableSpace } }
}
