package com.deepstudent.platform.database.internal

import com.deepstudent.platform.database.MigrationDiagnostic
import com.deepstudent.platform.database.MigrationException
import com.deepstudent.platform.database.SharedDatabase
import java.time.Instant

internal class GovernanceStore(private val session: SqliteSession, private val database: SharedDatabase) {
    /** Never execute writes against a view, altered contract, or attacker-supplied trigger. */
    fun validateStructure() {
        val type = session.query("SELECT type FROM sqlite_master WHERE name='__governance_schema_fingerprints'") { it.getText(0) }
        if (type == null) return
        if (type != "table") drift()
        val columns = session.queryAll("PRAGMA table_xinfo('__governance_schema_fingerprints')") { row ->
            GovernanceColumn(row.getText(1), row.getText(2).uppercase(), row.getLong(3), row.isNull(4), row.getLong(5), row.getLong(6))
        }
        if (columns != currentGovernanceColumns && columns != legacyGovernanceColumns) drift()
        if (session.queryLong("SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type='trigger' AND tbl_name='__governance_schema_fingerprints')") != 0L) drift()
    }

    private fun drift(): Nothing = throw MigrationException(MigrationDiagnostic.SCHEMA_DRIFT, database, objectType = "governance")

    fun ensureTable() {
        session.exec(
            "CREATE TABLE IF NOT EXISTS __governance_schema_fingerprints (" +
                "database_id TEXT NOT NULL, schema_version INTEGER NOT NULL, fingerprint TEXT NOT NULL, " +
                "verified_at TEXT NOT NULL, canonical_schema TEXT, PRIMARY KEY (database_id, schema_version))",
        )
        val columns = session.queryAll("PRAGMA table_info('__governance_schema_fingerprints')") { it.getText(1) }
        if ("canonical_schema" !in columns) {
            session.exec("ALTER TABLE __governance_schema_fingerprints ADD COLUMN canonical_schema TEXT")
        }
    }

    fun verify(version: Int, actual: CanonicalSchema): Boolean {
        val exists = session.queryLong(
            "SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name='__governance_schema_fingerprints')",
        ) == 1L
        if (!exists) return false
        val columns = session.queryAll("PRAGMA table_info('__governance_schema_fingerprints')") { it.getText(1) }
        val canonicalColumn = if ("canonical_schema" in columns) "canonical_schema" else "NULL"
        val recorded = session.query(
            "SELECT fingerprint, $canonicalColumn FROM __governance_schema_fingerprints " +
                "WHERE database_id=? AND schema_version=?",
            { it.bindText(1, database.id); it.bindLong(2, version.toLong()) },
        ) { row -> row.getText(0) to if (row.isNull(1)) null else row.getText(1) }
        if (recorded != null && (recorded.first != actual.hash || recorded.second?.let { it != actual.text } == true)) {
            throw MigrationException(MigrationDiagnostic.SCHEMA_DRIFT, database, version, "governance")
        }
        return recorded != null
    }

    fun store(version: Int, actual: CanonicalSchema) {
        session.execBind(
            "INSERT INTO __governance_schema_fingerprints " +
                "(database_id,schema_version,fingerprint,verified_at,canonical_schema) VALUES(?,?,?,?,?) " +
                "ON CONFLICT(database_id,schema_version) DO NOTHING",
        ) {
            it.bindText(1, database.id)
            it.bindLong(2, version.toLong())
            it.bindText(3, actual.hash)
            it.bindText(4, Instant.now().toString())
            it.bindText(5, actual.text)
        }
    }
}

private data class GovernanceColumn(
    val name: String, val type: String, val notNull: Long, val noDefault: Boolean, val primaryKey: Long, val hidden: Long,
)

private val legacyGovernanceColumns = listOf(
    GovernanceColumn("database_id", "TEXT", 1, true, 1, 0),
    GovernanceColumn("schema_version", "INTEGER", 1, true, 2, 0),
    GovernanceColumn("fingerprint", "TEXT", 1, true, 0, 0),
    GovernanceColumn("verified_at", "TEXT", 1, true, 0, 0),
)

private val currentGovernanceColumns = legacyGovernanceColumns + GovernanceColumn("canonical_schema", "TEXT", 0, true, 0, 0)
