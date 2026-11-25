package com.deepstudent.app.diagnostics

import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import java.io.File

/** Only sanitized entries are retained, bounded in memory and optionally in private history. */
internal class S231DiagnosticBuffer(initial: List<S231Entry> = emptyList(), private val onChanged: (S231Snapshot) -> Unit = {}) : Logger {
    private val projection = S231Projection(initial)
    private val entries = ArrayDeque(initial.takeLast(S231Snapshot.MAX_RECORDS))

    @Synchronized
    override fun log(record: LogRecord) {
        val safe = projection.entry(record)
        if (entries.size == S231Snapshot.MAX_RECORDS) entries.removeFirst()
        entries.addLast(safe)
        onChanged(S231Snapshot(entries.toList()))
    }

    @Synchronized
    fun snapshot(): S231Snapshot = S231Snapshot(entries.toList())
}

/** Only this app-owned sanitized history is read; never raw files or system logs. */
internal object S231Diagnostics {
    @Volatile
    var buffer = S231DiagnosticBuffer()
        private set
    private var initialized = false

    @Synchronized
    fun initialize(directory: File) {
        if (initialized) return
        val history = S231History(directory)
        val writer = S231HistoryWriter(history)
        buffer = S231DiagnosticBuffer(history.read(), writer::submit)
        initialized = true
    }

    fun record(record: LogRecord) {
        buffer.log(record)
    }
}
