package com.deepstudent.app.storage

import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase

/** Extra database leases share the active slot and open on first use by their domain or sync. */
internal class MainSyncDatabases(private val vfs: SqlDatabase, open: (SharedDatabase) -> SqlDatabase) : AutoCloseable {
    private val extra = SharedDatabase.entries.filter { it != SharedDatabase.VFS }.associateWith { owner ->
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) { open(owner) }
    }

    @Synchronized
    fun get(owner: SharedDatabase): SqlDatabase {
        check(!closed)
        return if (owner == SharedDatabase.VFS) vfs else extra.getValue(owner).value
    }

    @Synchronized
    fun all(): Map<SharedDatabase, SqlDatabase> = SharedDatabase.entries.associateWith(::get)

    private var closed = false

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        var failed: Throwable? = null
        for (lease in extra.values) {
            if (!lease.isInitialized()) continue
            try {
                lease.value.close()
            } catch (problem: Throwable) {
                val first = failed
                if (first == null) failed = problem else first.addSuppressed(problem)
            }
        }
        failed?.let { throw it }
    }
}
