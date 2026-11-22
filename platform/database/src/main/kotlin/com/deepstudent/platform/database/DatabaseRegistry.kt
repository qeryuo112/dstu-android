package com.deepstudent.platform.database

import com.deepstudent.platform.database.internal.RegistryOwner
import java.io.File

/** Per-connection durability and finite contention budget. Values must agree for all leases of a file. */
public data class DatabaseProfile(
    val lockWaitMillis: Long = 5_000,
    val maxReaders: Int = 4,
    val synchronous: DatabaseSynchronous = DatabaseSynchronous.FULL,
) {
    init {
        require(lockWaitMillis in 1..60_000)
        require(maxReaders in 1..16)
    }

    public companion object {
        public fun shared(database: SharedDatabase): DatabaseProfile = when (database) {
            SharedDatabase.VFS -> DatabaseProfile(synchronous = DatabaseSynchronous.NORMAL)
            SharedDatabase.CHAT_V2 -> DatabaseProfile(10_000, synchronous = DatabaseSynchronous.NORMAL)
            SharedDatabase.MISTAKES -> DatabaseProfile(3_000)
            SharedDatabase.LLM_USAGE -> DatabaseProfile(3_000, synchronous = DatabaseSynchronous.NORMAL)
        }
    }
}

public enum class DatabaseSynchronous { FULL, NORMAL }

/** Stable timeout stages; no filesystem path, SQL, values, or driver message is exposed. */
public enum class DatabaseWait { REGISTRY, WRITER, SQLITE, READER, CLOSE }
public class DatabaseBusyException(public val stage: DatabaseWait) :
    IllegalStateException("database wait budget exhausted: ${stage.name}")

public class DatabaseConfigurationException : IllegalStateException("database configuration conflicts with an active owner")

/** Only fixed stage/status and elapsed time are emitted, never user data or arbitrary exception text. */
public data class DatabaseLogEvent(
    val phase: DatabasePhase,
    val status: DatabaseStatus,
    val elapsedMillis: Long,
    val wait: DatabaseWait? = null,
    val owner: String = "platform/database",
)
public enum class DatabasePhase { OPEN, WRITE, READ, CLOSE }
public enum class DatabaseStatus { SUCCESS, FAILED, TIMED_OUT }
public fun interface DatabaseLog {
    public fun onEvent(event: DatabaseLogEvent)
    public companion object { public val NONE: DatabaseLog = DatabaseLog { } }
}

/**
 * Process-wide canonical-path ownership. Each call returns a separately closeable lease;
 * all leases of one file share one serialized writer. Cross-process writers are governed
 * by SQLite's locks and the same wait budget. No raw connection is exposed.
 */
public object DatabaseRegistry {
    public fun openLocal(
        file: File,
        profile: DatabaseProfile = DatabaseProfile(),
        log: DatabaseLog = DatabaseLog.NONE,
    ): SqlDatabase = RegistryOwner.openLocal(file, profile, log)

    public fun openShared(
        file: File,
        database: SharedDatabase,
        source: MigrationSource,
        profile: DatabaseProfile = DatabaseProfile.shared(database),
        log: DatabaseLog = DatabaseLog.NONE,
        migrationLog: MigrationLog = MigrationLog.NONE,
    ): SqlDatabase = RegistryOwner.openShared(file, database, source, profile, log, migrationLog)
}
