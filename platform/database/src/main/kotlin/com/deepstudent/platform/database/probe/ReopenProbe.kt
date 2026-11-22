package com.deepstudent.platform.database.probe

import com.deepstudent.platform.database.ProbeId
import com.deepstudent.platform.database.ProbeResult
import com.deepstudent.platform.database.internal.ProbeContext
import com.deepstudent.platform.database.internal.ProbeFamily
import com.deepstudent.platform.database.internal.expectEquals

internal class ReopenProbe : ProbeFamily {
    override fun run(context: ProbeContext): List<ProbeResult> = listOf(
        context.capture(ProbeId.REOPEN) {
            val fileName = "reopen.db"
            context.withSession(fileName) { session ->
                session.exec("pragma journal_mode=wal")
                session.exec("create table persisted(id integer primary key, value text not null)")
                session.execBind("insert into persisted(value) values(?)") {
                    it.bindText(1, "中文-reopen")
                }
            }
            context.withSession(fileName) { reopened ->
                val count = reopened.queryLong("select count(*) from persisted")
                val value = reopened.queryText("select value from persisted where id = 1")
                val journalMode = reopened.queryText("pragma journal_mode")
                val foreignKeys = reopened.queryLong("pragma foreign_keys")
                expectEquals(count, 1L, "reopened row count")
                expectEquals(value, "中文-reopen", "reopened UTF-8 text")
                mapOf(
                    "rowCount" to count.toString(),
                    "value" to value,
                    "journalMode" to journalMode,
                    "defaultForeignKeys" to foreignKeys.toString(),
                )
            }
        },
    )
}
