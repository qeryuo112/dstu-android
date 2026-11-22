package com.deepstudent.platform.database.internal

import com.deepstudent.platform.database.DatabaseError
import com.deepstudent.platform.database.DatabaseDiagnostic
import com.deepstudent.platform.database.DatabaseErrorKind
import com.deepstudent.platform.database.DatabaseErrorSink
import com.deepstudent.platform.database.DatabaseProbeLog
import com.deepstudent.platform.database.ProbeId
import com.deepstudent.platform.database.ProbeLogEvent
import com.deepstudent.platform.database.ProbeResult
import com.deepstudent.platform.database.ProbeStatus

/** Shared services for independent probe families. */
internal class ProbeContext(
    val factory: SqliteConnectionFactory,
    val workspace: ProbeWorkspace,
    val owner: String,
    val correlationId: String,
    private val errorSink: DatabaseErrorSink,
    private val log: DatabaseProbeLog,
) {
    fun report(error: DatabaseError): DatabaseError {
        val correlated = error.copy(owner = owner, correlationId = correlationId)
        runCatching { errorSink.onError(correlated) }
        return correlated
    }

    fun <T> withSession(fileName: String, block: (SqliteSession) -> T): T =
        factory.open(workspace.file(fileName)).use(block)

    @Suppress("TooGenericExceptionCaught")
    fun capture(id: ProbeId, block: () -> Map<String, String>): ProbeResult {
        val started = System.nanoTime()
        val result = try {
            val details = block()
            ProbeResult(
                id = id,
                status = ProbeStatus.PASSED,
                durationMillis = elapsedMillis(started),
                details = details,
            )
        } catch (unsupported: ProbeUnsupported) {
            ProbeResult(
                id = id,
                status = ProbeStatus.UNSUPPORTED,
                durationMillis = elapsedMillis(started),
                details = mapOf("reason" to unsupported.reason),
            )
        } catch (failure: DatabaseFailure) {
            failed(id, started, failure.error)
        } catch (assertion: ProbeAssertionFailure) {
            failed(
                id,
                started,
                DatabaseError(
                    kind = DatabaseErrorKind.ASSERTION,
                    diagnostic = DatabaseDiagnostic.PROBE_ASSERTION,
                    operation = id.name,
                ),
                details = mapOf("assertion" to assertion.label),
            )
        } catch (failure: Exception) {
            failed(id, started, unexpectedError(id.name, failure))
        }
        emit(result)
        return result
    }

    private fun failed(
        id: ProbeId,
        started: Long,
        error: DatabaseError,
        details: Map<String, String> = emptyMap(),
    ): ProbeResult {
        val correlated = report(error)
        return ProbeResult(
            id = id,
            status = ProbeStatus.FAILED,
            durationMillis = elapsedMillis(started),
            details = details,
            error = correlated,
        )
    }

    private fun elapsedMillis(started: Long): Long =
        (System.nanoTime() - started) / NANOS_PER_MILLI

    private fun emit(result: ProbeResult) {
        val event = ProbeLogEvent(
            owner = owner,
            correlationId = correlationId,
            probeId = result.id,
            status = result.status.name,
            durationMillis = result.durationMillis,
            detailKeys = result.details.keys,
            errorKind = result.error?.kind?.name,
            errorDiagnostic = result.error?.diagnostic?.name,
        )
        runCatching { log.onEvent(event) }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
