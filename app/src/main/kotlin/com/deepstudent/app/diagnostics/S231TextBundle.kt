package com.deepstudent.app.diagnostics

/**
 * A deterministic allowlisted text bundle, later shared only after an explicit user action.
 * Its only input is a previously projected snapshot; it never opens logs or repositories.
 */
internal object S231TextBundle {
    const val FILE_NAME = "dstu-diagnostics-s231.txt"
    const val MAX_BYTES = 64 * 1024

    fun create(snapshot: S231Snapshot): ByteArray {
        val text = buildString {
            append("dstu-diagnostics-s231-v1\n")
            append("privacy=allowlisted-structured-fields-only\n")
            append("records=").append(snapshot.entries.size).append('\n')
            snapshot.entries.forEachIndexed { index, entry ->
                append("record=").append(index + 1)
                appendIdentity(entry)
                appendLocation(entry.location)
                appendProgress(entry.progress)
                append(" error=").append(entry.error?.name ?: "-")
                append('\n')
            }
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        // The finite enums and record cap make this an invariant, not an input-dependent truncation.
        check(bytes.size <= MAX_BYTES) { "diagnostic text exceeds its fixed output budget" }
        return bytes
    }

    private fun StringBuilder.appendIdentity(entry: S231Entry) {
        append(" level=").append(entry.level.name)
        append(" event=").append(entry.event?.name ?: "-")
        append(" library=").append(entry.library?.let { "L$it" } ?: "-")
        append(" task=").append(entry.task?.let { "T$it" } ?: "-")
        append(" correlation=").append(entry.correlation?.let { "C$it" } ?: "-")
    }

    private fun StringBuilder.appendLocation(location: S231Location) {
        append(" owner=").append(location.owner?.name ?: "-")
        append(" database=").append(location.database?.name ?: "-")
        append(" stage=").append(location.stage?.name ?: "-")
        append(" phase=").append(location.phase?.name ?: "-")
    }

    private fun StringBuilder.appendProgress(progress: S231Progress) {
        append(" status=").append(progress.status?.name ?: "-")
        append(" state=").append(progress.state?.name ?: "-")
        append(" outcome=").append(progress.outcome?.name ?: "-")
        append(" duration_ms=").append(progress.durationMillis?.toString() ?: "-")
    }
}
