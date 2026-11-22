package com.deepstudent.platform.database.probe

import androidx.sqlite.SQLiteStatement
import com.deepstudent.platform.database.ProbeId
import com.deepstudent.platform.database.ProbeResult
import com.deepstudent.platform.database.internal.ProbeContext
import com.deepstudent.platform.database.internal.ProbeFamily
import com.deepstudent.platform.database.internal.ProbeUnsupported
import com.deepstudent.platform.database.internal.SqliteSession
import com.deepstudent.platform.database.internal.expectEquals
import com.deepstudent.platform.database.internal.expectTrue
import com.deepstudent.platform.database.internal.sqliteVersionAtLeast

internal class FtsProbe : ProbeFamily {
    override fun run(context: ProbeContext): List<ProbeResult> = listOf(
        context.capture(ProbeId.FTS5_TRIGRAM) { trigram(context) },
        context.capture(ProbeId.FTS5_CONTENTLESS_DELETE) { contentlessDelete(context) },
    )

    private fun trigram(context: ProbeContext): Map<String, String> =
        context.withSession("fts-trigram.db") { session ->
            session.exec("create virtual table docs using fts5(body, tokenize='trigram')")
            insert(session, 1, "中华人民共和国")
            insert(session, 2, "中文English混合测试")
            insert(session, 3, "Hello World hello")

            val chinese = matchIds(session, "中华人")
            val chineseFull = matchIds(session, "中华人民共和国")
            val mixed = matchIds(session, "中文E")
            val english = matchIds(session, "English")
            val ascii = matchIds(session, "hello")
            val likeChineseShort = likeIds(session, "%人民%")

            expectEquals(chinese, "1", "trigram CJK match")
            expectEquals(chineseFull, "1", "trigram full CJK match")
            expectEquals(mixed, "2", "trigram mixed CJK/ASCII match")
            expectEquals(english, "2", "trigram English match")
            expectEquals(ascii, "3", "trigram lowercase English match")
            expectEquals(likeChineseShort, "1", "trigram LIKE short CJK match")

            mapOf(
                "tokenizer" to "trigram",
                "chineseMatch" to chinese,
                "chineseFullMatch" to chineseFull,
                "mixedMatch" to mixed,
                "englishMatch" to english,
                "asciiMatch" to ascii,
                "twoCharacterCjkMatch" to likeChineseShort,
                "twoCharacterCjkNote" to "MATCH needs three Unicode characters; LIKE covers shorter CJK",
            )
        }

    private fun contentlessDelete(context: ProbeContext): Map<String, String> =
        context.withSession("fts-contentless-delete.db") { session ->
            val version = session.queryText("select sqlite_version()")
            if (!sqliteVersionAtLeast(version, "3.43.0")) {
                throw ProbeUnsupported("contentless_delete requires SQLite >= 3.43.0, actual $version")
            }
            session.exec(
                "create virtual table docs_delete using fts5(" +
                    "body, content='', contentless_delete=1)",
            )
            insertDelete(session, 1, "alpha beta")
            insertDelete(session, 2, "gamma delta")
            val before = matchDeleteIds(session, "alpha")
            session.exec("delete from docs_delete where rowid = 1")
            val after = matchDeleteIds(session, "alpha")
            insertDelete(session, 1, "alpha again")
            val reinserted = matchDeleteIds(session, "alpha")

            expectTrue(sqliteVersionAtLeast(version, "3.43.0"), "contentless_delete version gate")
            expectEquals(before, "1", "contentless_delete before delete")
            expectEquals(after, "", "contentless_delete after delete")
            expectEquals(reinserted, "1", "contentless_delete after reinsert")

            mapOf(
                "sqliteVersion" to version,
                "minimumVersion" to "3.43.0",
                "beforeDeleteMatch" to before,
                "afterDeleteMatch" to after,
                "afterReinsertMatch" to reinserted,
            )
        }

    private fun insert(session: SqliteSession, rowId: Int, body: String) {
        session.execBind("insert into docs(rowid, body) values(?, ?)") { statement ->
            statement.bindLong(1, rowId.toLong())
            statement.bindText(2, body)
        }
    }

    private fun insertDelete(session: SqliteSession, rowId: Int, body: String) {
        session.execBind("insert into docs_delete(rowid, body) values(?, ?)") { statement ->
            statement.bindLong(1, rowId.toLong())
            statement.bindText(2, body)
        }
    }

    private fun matchIds(session: SqliteSession, query: String): String =
        scalarText(session, "docs", query)

    private fun matchDeleteIds(session: SqliteSession, query: String): String =
        scalarText(session, "docs_delete", query)

    private fun scalarText(session: SqliteSession, table: String, query: String): String =
        session.query(
            sql = "select group_concat(rowid) from (" +
                "select rowid from $table where $table match ? order by rowid)",
            bind = { statement: SQLiteStatement -> statement.bindText(1, query) },
        ) { it.getText(0) } ?: ""

    private fun likeIds(session: SqliteSession, pattern: String): String =
        session.query(
            sql = "select group_concat(rowid) from (" +
                "select rowid from docs where body like ? order by rowid)",
            bind = { it.bindText(1, pattern) },
        ) { it.getText(0) } ?: ""
}
