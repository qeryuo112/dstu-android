package com.deepstudent.platform.database.probe

import com.deepstudent.platform.database.ProbeId
import com.deepstudent.platform.database.ProbeResult
import com.deepstudent.platform.database.internal.ProbeContext
import com.deepstudent.platform.database.internal.ProbeFamily
import com.deepstudent.platform.database.internal.captureDatabaseError
import com.deepstudent.platform.database.internal.expectEquals
import com.deepstudent.platform.database.internal.expectTrue
import com.deepstudent.platform.database.internal.guardSql

internal class ResourceProbe : ProbeFamily {
    override fun run(context: ProbeContext): List<ProbeResult> = listOf(
        context.capture(ProbeId.RESOURCE_CLOSE) {
            val file = context.workspace.file("resource.db")
            val session = context.factory.open(file)
            val statementUseError = captureDatabaseError {
                val statement = session.prepareRaw("select 1")
                statement.use { expectTrue(it.step(), "select 1 must return a row") }
                guardSql("statement-close-again") { statement.close() }
                guardSql("statement-after-close") { statement.step() }
            }
            guardSql("connection-close-again") {
                session.close()
                session.close()
            }
            val connectionUseError = captureDatabaseError {
                session.exec("select 1")
            }
            context.factory.open(file).use { reopened ->
                expectEquals(reopened.queryLong("select 1"), 1L, "reopened connection")
            }
            expectTrue(statementUseError != null, "closed statement must reject step()")
            expectTrue(connectionUseError != null, "closed connection must reject SQL")
            val statementCode = statementUseError?.sqliteCode
            val connectionCode = connectionUseError?.sqliteCode
            expectEquals(statementCode, 21, "closed statement misuse code")
            expectEquals(connectionCode, 21, "closed connection misuse code")
            mapOf(
                "statementCloseTwice" to "true",
                "statementAfterCloseCode" to statementCode.toString(),
                "connectionCloseTwice" to "true",
                "connectionAfterCloseCode" to connectionCode.toString(),
                "reopenAfterClose" to "true",
            )
        },
    )
}
