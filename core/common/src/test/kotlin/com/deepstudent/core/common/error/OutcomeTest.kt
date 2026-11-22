package com.deepstudent.core.common.error

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class OutcomeTest {
    private val sentinel = "SECRET=TOP-SECRET /home/user/private.db SELECT * FROM messages"

    @Test
    fun typedFailureKeepsCodeAndKind() {
        val outcome = outcomeCatching<Unit> {
            throw DstuException(DstuError.conflict("COLLISION", "different payload"))
        }
        val failure = outcome as Outcome.Failure
        assertEquals("COLLISION", failure.error.code)
        assertEquals(DstuErrorKind.CONFLICT, failure.error.kind)
    }

    @Test
    fun cancellationIsRethrownNotConvertedToFailure() {
        val cancellation = CancellationException("stop")
        val thrown = assertThrows(CancellationException::class.java) {
            outcomeCatching<Unit> { throw cancellation }
        }
        assertSame(cancellation, thrown)
    }

    @Test
    fun explicitCancellationPropagatesThroughMapAndFlatMap() {
        val mapped = (Outcome.Cancelled("cancelled") as Outcome<String>)
            .map { it.length }
            .flatMap { Outcome.Success(it) }
        assertTrue(mapped is Outcome.Cancelled)
        assertThrows(CancellationException::class.java) { mapped.getOrThrow() }
    }

    @Test
    fun unexpectedExceptionMessageNeverReachesDstuError() {
        val outcome = outcomeCatching<Unit> { throw IllegalArgumentException(sentinel) }
        val error = (outcome as Outcome.Failure).error
        assertEquals("UNEXPECTED", error.code)
        assertEquals("operation failed", error.message)
        assertEquals(mapOf("causeClass" to "java.lang.IllegalArgumentException"), error.details)
        assertFalse(error.toString().contains("TOP-SECRET"))
        assertFalse(error.toString().contains("/home/user"))
        assertFalse(error.toString().contains("SELECT"))

        val unlisted = errorFromThrowable(RuntimeException(sentinel))
        assertEquals("UNEXPECTED", unlisted.code)
        assertEquals("operation failed", unlisted.message)
        assertTrue(unlisted.details.isEmpty())
        assertFalse(unlisted.toString().contains("TOP-SECRET"))
    }

    @Test
    fun cancellationMessageNeverReachesDstuError() {
        val error = CancellationException(sentinel).asCancellationError()
        assertEquals("CANCELLED", error.code)
        assertEquals("operation cancelled", error.message)
        assertTrue(error.details.isEmpty())
        assertFalse(error.toString().contains("TOP-SECRET"))
    }

    @Test
    fun typedDstuExceptionKeepsExplicitTrustedFields() {
        val typed = DstuError.validation("TYPED", "trusted public validation detail")
        assertSame(typed, errorFromThrowable(DstuException(typed)))
    }

    @Test
    fun fatalErrorsPropagateInsteadOfBecomingBusinessFailures() {
        val linkage = LinkageError(sentinel)
        assertSame(
            linkage,
            assertThrows(LinkageError::class.java) {
                outcomeCatching<Unit> { throw linkage }
            },
        )
        val assertion = AssertionError(sentinel)
        assertSame(
            assertion,
            assertThrows(AssertionError::class.java) {
                runBlocking { suspendOutcomeCatching<Unit> { throw assertion } }
            },
        )
    }
}
