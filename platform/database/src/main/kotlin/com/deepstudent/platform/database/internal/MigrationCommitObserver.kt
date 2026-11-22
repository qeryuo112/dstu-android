package com.deepstudent.platform.database.internal

import com.deepstudent.platform.database.SharedDatabase

/** Internal process-local test seam; no production configuration or IPC can install it. */
internal object MigrationCommitObserver {
    @Volatile var onCommit: ((MigrationCommit) -> Unit)? = null
}

internal enum class MigrationCommitKind { MIGRATION, GOVERNANCE }

internal data class MigrationCommit(val database: SharedDatabase, val version: Int, val kind: MigrationCommitKind)
