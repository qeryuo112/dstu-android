package com.deepstudent.services.backup.backend

import kotlinx.serialization.json.JsonObject

/** Optional exporter self-check is untrusted: validate it, never derive authority from it. */
internal fun validateContinuityExpected(
    root: JsonObject, state: ContinuityState, repository: ContinuityRepository,
    writer: String, takeoverKey: String, checkCancelled: () -> Unit,
) {
    if (!root.containsKey("expected")) return
    val expected = root.objectValue("expected")
    expected.shape(setOf("state", "duplicate_rejected"))
    continuityRequire(expected.bool("duplicate_rejected"))
    val declared = parseContinuityState(expected.objectValue("state"), repository, writer, checkCancelled)
    val same = declared.repository == state.repository && declared.upload == state.upload &&
        declared.cursors.toSet() == state.cursors.toSet() && declared.watermarks.toSet() == state.watermarks.toSet() &&
        declared.publications.toSet() == state.publications.toSet() &&
        declared.processedKeys.toSet() == (state.processedKeys + takeoverKey).toSet()
    continuityRequire(same, ContinuityFailure.INVALID_STATE)
}
