package com.deepstudent.platform.database.probe

import androidx.sqlite.SQLiteStatement
import com.deepstudent.platform.database.ProbeId
import com.deepstudent.platform.database.ProbeResult
import com.deepstudent.platform.database.internal.ProbeContext
import com.deepstudent.platform.database.internal.ProbeFamily
import com.deepstudent.platform.database.internal.SqliteSession
import com.deepstudent.platform.database.internal.expectEquals

internal class JsonProbe : ProbeFamily {
    override fun run(context: ProbeContext): List<ProbeResult> = listOf(
        context.capture(ProbeId.JSON1) { json1(context) },
        context.capture(ProbeId.JSONB) { jsonb(context) },
    )

    private fun json1(context: ProbeContext): Map<String, String> =
        context.withSession("json1.db") { session ->
            val document = """{"a":{"b":[1,2,{"c":"中文"}]}}"""
            val extracted = text(session, "select json_extract(?, '$.a.b[2].c')", document)
            val type = text(session, "select json_type(?)", document)
            val length = number(session, "select json_array_length(?)", """[1,2,3]""")
            val compileOption =
                session.queryLong("select sqlite_compileoption_used('ENABLE_JSON1')") == 1L

            expectEquals(extracted, "中文", "json_extract")
            expectEquals(type, "object", "json_type")
            expectEquals(length, 3L, "json_array_length")
            mapOf(
                "jsonExtract" to extracted,
                "jsonType" to type,
                "jsonArrayLength" to length.toString(),
                "json1CompileOption" to compileOption.toString(),
            )
        }

    private fun jsonb(context: ProbeContext): Map<String, String> =
        context.withSession("jsonb.db") { session ->
            val document = """{"a":{"b":2}}"""
            val type = text(session, "select json_type(jsonb(?))", document)
            val extracted = number(session, "select json_extract(jsonb(?), '$.a.b')", document)
            val hex = text(session, "select hex(jsonb(?))", """{"a":1}""")

            expectEquals(type, "object", "jsonb json_type")
            expectEquals(extracted, 2L, "jsonb json_extract")
            expectEquals(hex, "4C17611331", "jsonb hex")
            mapOf(
                "jsonbType" to type,
                "jsonbExtract" to extracted.toString(),
                "jsonbHex" to hex,
            )
        }

    private fun text(
        session: SqliteSession,
        sql: String,
        value: String,
    ): String = session.query(sql, bindText(value)) { it.getText(0) } ?: ""

    private fun number(
        session: SqliteSession,
        sql: String,
        value: String,
    ): Long = session.query(sql, bindText(value)) { it.getLong(0) } ?: -1L

    private fun bindText(value: String): (SQLiteStatement) -> Unit = { it.bindText(1, value) }
}
