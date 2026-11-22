package com.deepstudent.platform.database.probe

import com.deepstudent.platform.database.DatabaseErrorKind
import com.deepstudent.platform.database.ProbeId
import com.deepstudent.platform.database.ProbeResult
import com.deepstudent.platform.database.internal.ProbeContext
import com.deepstudent.platform.database.internal.ProbeFamily
import com.deepstudent.platform.database.internal.captureDatabaseError
import com.deepstudent.platform.database.internal.expectEquals
import com.deepstudent.platform.database.internal.expectTrue

internal class ForeignKeyProbe : ProbeFamily {
    override fun run(context: ProbeContext): List<ProbeResult> = listOf(
        context.capture(ProbeId.FOREIGN_KEYS) {
            context.withSession("foreign-keys.db") { session ->
                session.exec("create table parent(id integer primary key)")
                session.exec(
                    "create table child(" +
                        "id integer primary key, " +
                        "parent_id integer references parent(id))",
                )
                val defaultSetting = session.queryLong("pragma foreign_keys")
                session.exec("pragma foreign_keys=on")
                val enabledSetting = session.queryLong("pragma foreign_keys")
                expectEquals(enabledSetting, 1L, "PRAGMA foreign_keys after enable")

                session.exec("insert into parent(id) values(1)")
                session.exec("insert into child(id, parent_id) values(1, 1)")
                val violation = captureDatabaseError {
                    session.exec("insert into child(id, parent_id) values(2, 99)")
                }
                expectTrue(violation != null, "foreign key violation must fail")
                val error = violation ?: error("unreachable")
                context.report(error)
                expectEquals(error.kind, DatabaseErrorKind.CONSTRAINT, "foreign key error kind")
                expectEquals(error.sqliteCode, 787, "foreign key error code")

                mapOf(
                    "defaultForeignKeys" to defaultSetting.toString(),
                    "enabledForeignKeys" to enabledSetting.toString(),
                    "validChildRows" to session.queryLong("select count(*) from child").toString(),
                    "violationKind" to error.kind.name,
                    "violationCode" to error.sqliteCode.toString(),
                    "violationDiagnostic" to error.diagnostic.name,
                )
            }
        },
    )
}
