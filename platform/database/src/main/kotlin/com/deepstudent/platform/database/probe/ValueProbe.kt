package com.deepstudent.platform.database.probe

import com.deepstudent.platform.database.ProbeId
import com.deepstudent.platform.database.ProbeResult
import com.deepstudent.platform.database.internal.ProbeContext
import com.deepstudent.platform.database.internal.ProbeFamily
import com.deepstudent.platform.database.internal.expectEquals
import com.deepstudent.platform.database.internal.expectTrue

internal class ValueProbe : ProbeFamily {
    override fun run(context: ProbeContext): List<ProbeResult> = listOf(
        context.capture(ProbeId.INTEGER_BOUNDARIES) { integers(context) },
        context.capture(ProbeId.BLOB_NUL) { blob(context) },
        context.capture(ProbeId.NULL_VALUE) { nulls(context) },
    )

    private fun integers(context: ProbeContext): Map<String, String> =
        context.withSession("integers.db") { session ->
            session.exec("create table ints(key text primary key, value integer not null)")
            for (case in INTEGER_CASES) {
                session.execBind("insert into ints(key, value) values(?, ?)") { statement ->
                    statement.bindText(1, case.key)
                    statement.bindLong(2, case.value)
                }
            }
            for (case in INTEGER_CASES) {
                val actual = session.queryLong(
                    "select value from ints where key = ?",
                ) { it.bindText(1, case.key) }
                expectEquals(actual, case.value, "integer ${case.key}")
            }
            val types = INTEGER_CASES.joinToString(",") { case ->
                session.queryText(
                    "select typeof(value) from ints where key = ?",
                ) { it.bindText(1, case.key) }
            }
            val expectedTypes = List(INTEGER_CASES.size) { "integer" }.joinToString(",")
            expectEquals(types, expectedTypes, "integer typeof values")
            INTEGER_CASES.associate { it.key to it.value.toString() } + mapOf(
                "types" to types,
                "count" to INTEGER_CASES.size.toString(),
            )
        }

    private fun blob(context: ProbeContext): Map<String, String> =
        context.withSession("blob.db") { session ->
            session.exec("create table blobs(key text primary key, value blob)")
            val bytes = byteArrayOf(0, 1, 2, 0, 127, -1)
            session.execBind("insert into blobs(key, value) values('blob', ?)") {
                it.bindBlob(1, bytes)
            }
            val length = session.queryLong("select length(value) from blobs where key = 'blob'")
            val hex = session.queryText("select hex(value) from blobs where key = 'blob'")
            val readBack = session.query("select value from blobs where key = 'blob'") {
                it.getBlob(0)
            } ?: error("blob row missing")
            expectEquals(length, bytes.size.toLong(), "blob length")
            expectEquals(hex, "000102007FFF", "blob hex")
            expectTrue(readBack.contentEquals(bytes), "blob bytes with embedded NUL")
            mapOf(
                "blobLength" to length.toString(),
                "blobHex" to hex,
                "embeddedNulPreserved" to "true",
            )
        }

    private fun nulls(context: ProbeContext): Map<String, String> =
        context.withSession("nulls.db") { session ->
            session.exec("create table nullable(key text primary key, i integer, b blob, t text)")
            session.execBind("insert into nullable(key, i, b, t) values('nulls', ?, ?, ?)") {
                it.bindNull(1)
                it.bindNull(2)
                it.bindNull(3)
            }
            val row = session.query("select i, b, t from nullable where key = 'nulls'") {
                listOf(it.isNull(0), it.isNull(1), it.isNull(2))
            } ?: error("null row missing")
            val type = session.queryText("select typeof(i) from nullable where key = 'nulls'")
            expectTrue(row.all { it }, "all bound NULL columns read as NULL")
            expectEquals(type, "null", "typeof NULL")
            mapOf(
                "allColumnsNull" to row.all { it }.toString(),
                "typeof" to type,
                "integerNull" to row[0].toString(),
                "blobNull" to row[1].toString(),
                "textNull" to row[2].toString(),
            )
        }

    private data class IntegerCase(val key: String, val value: Long)

    private companion object {
        val INTEGER_CASES = listOf(
            IntegerCase("min", Long.MIN_VALUE),
            IntegerCase("minPlusOne", Long.MIN_VALUE + 1),
            IntegerCase("minusOne", -1L),
            IntegerCase("zero", 0L),
            IntegerCase("one", 1L),
            IntegerCase("largeExact", 9_007_199_254_740_993L),
            IntegerCase("maxMinusOne", Long.MAX_VALUE - 1),
            IntegerCase("max", Long.MAX_VALUE),
        )
    }
}
