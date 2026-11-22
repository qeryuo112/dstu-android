package com.deepstudent.platform.database.probe

import com.deepstudent.platform.database.ProbeId
import com.deepstudent.platform.database.ProbeResult
import com.deepstudent.platform.database.internal.ProbeContext
import com.deepstudent.platform.database.internal.ProbeFamily
import com.deepstudent.platform.database.internal.SqliteSession
import com.deepstudent.platform.database.internal.expectEquals
import com.deepstudent.platform.database.internal.expectTrue

internal class TransactionProbe : ProbeFamily {
    override fun run(context: ProbeContext): List<ProbeResult> = listOf(
        context.capture(ProbeId.TRANSACTION) { transaction(context) },
        context.capture(ProbeId.SAVEPOINT) { savepoint(context) },
    )

    private fun transaction(context: ProbeContext): Map<String, String> =
        context.withSession("transaction.db") { session ->
            session.exec("create table tx(id integer primary key, value text)")
            expectEquals(session.inTransaction(), false, "fresh connection transaction state")

            session.exec("begin")
            insert(session, "tx", "committed")
            expectEquals(session.inTransaction(), true, "inTransaction after BEGIN")
            session.exec("commit")
            expectEquals(session.inTransaction(), false, "inTransaction after COMMIT")

            session.exec("begin")
            insert(session, "tx", "rolled-back")
            session.exec("rollback")
            expectEquals(session.inTransaction(), false, "inTransaction after ROLLBACK")

            val count = session.queryLong("select count(*) from tx")
            val values = values(session, "tx")
            expectEquals(count, 1L, "transaction committed row count")
            expectEquals(values, "committed", "transaction values")
            mapOf(
                "committedCount" to count.toString(),
                "committedValues" to values,
                "inTransactionAfterCommit" to "false",
                "inTransactionAfterRollback" to "false",
            )
        }

    private fun savepoint(context: ProbeContext): Map<String, String> =
        context.withSession("savepoint.db") { session ->
            session.exec("create table sp_rollback(id integer primary key, value text)")
            session.exec("begin")
            insert(session, "sp_rollback", "outer")
            session.exec("savepoint sp1")
            insert(session, "sp_rollback", "inner")
            session.exec("rollback to sp1")
            session.exec("release sp1")
            session.exec("commit")
            val rollbackValues = values(session, "sp_rollback")

            session.exec("create table sp_release(id integer primary key, value text)")
            session.exec("begin")
            insert(session, "sp_release", "outer")
            session.exec("savepoint sp2")
            insert(session, "sp_release", "inner")
            session.exec("release sp2")
            session.exec("commit")
            val releaseValues = values(session, "sp_release")

            expectEquals(rollbackValues, "outer", "ROLLBACK TO savepoint values")
            expectEquals(releaseValues, "outer,inner", "RELEASE savepoint values")
            mapOf(
                "rollbackValues" to rollbackValues,
                "releaseValues" to releaseValues,
                "rollbackRows" to count(session, "sp_rollback").toString(),
                "releaseRows" to count(session, "sp_release").toString(),
            )
        }

    private fun insert(session: SqliteSession, table: String, value: String) {
        session.execBind("insert into $table(value) values(?)") { it.bindText(1, value) }
    }

    private fun values(session: SqliteSession, table: String): String =
        session.queryText(
            "select group_concat(value) from (" +
                "select value from $table order by id)",
        )

    private fun count(session: SqliteSession, table: String): Long =
        session.queryLong("select count(*) from $table")
}
