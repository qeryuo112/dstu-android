package com.deepstudent.services.sync.backend

import com.deepstudent.services.sync.api.SyncError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ContinuityRemoteClaimTest {
    @Test fun claimIsImmutableNamespacedAndReentrantOnlyForPendingOperation() = runBlocking {
        val memory = ClaimMemory()
        val ledger = ClaimLedger()
        val request = claimRequest()
        ledger.set(request)
        val service = claimService(memory, ledger)
        assertEquals(request, service.claim(request).request)
        assertEquals(request, service.claim(request).request)
        assertEquals(1, memory.claims().size)
        val (key, value) = memory.claims().entries.single()
        assertTrue(key.matches(Regex("dstu-management/continuity-claims/v1/[a-f0-9]{64}\\.claim")))
        val text = value.toString(Charsets.UTF_8)
        assertFalse(text.contains(request.instanceId))
        assertFalse(text.contains(request.writerId))
        assertTrue(text.contains(request.archiveSha256))
        assertEquals(2, memory.putCount.get())
        assertEquals(0, memory.deleteCount.get())
        ledger.set(request, ContinuityClaimAttemptStatus.COMPLETED)
        assertReason(ContinuityClaimFailure.ATTEMPT_NOT_PENDING) { service.claim(request) }
        assertEquals(2, memory.putCount.get())
        val duplicate = request.copy(operationId = claimRequest().operationId)
        ledger.set(duplicate)
        assertReason(ContinuityClaimFailure.ALREADY_CLAIMED) { service.claim(duplicate) }
        assertEquals(0, memory.deleteCount.get())
    }

    @Test fun claimIntentMustBeDurableBeforeAnyConditionalWrite() = runBlocking {
        val memory = ClaimMemory()
        val ledger = ClaimLedger()
        val request = claimRequest().also { ledger.set(it) }
        var attempted = 0
        val service = ContinuityRemoteClaim(memory, claimConditionalProof, claimRetirementProof, ledger,
            beforeConditionalCreate = { observed ->
                assertEquals(request, observed)
                assertEquals(0, memory.putCount.get())
                attempted++
                error("intent persistence failed")
            })
        assertReason(ContinuityClaimFailure.DEPENDENCY_UNAVAILABLE) { service.claim(request) }
        assertEquals(1, attempted)
        assertEquals(0, memory.putCount.get())
        assertTrue(memory.claims().isEmpty())
    }

    @Test fun concurrentDifferentTargetsHaveExactlyOneWinner() = runBlocking {
        val memory = ClaimMemory()
        val ledger = ClaimLedger()
        val arrivals = AtomicInteger()
        val both = CompletableDeferred<Unit>()
        val racing = object : ObjectProvider by memory {
            override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) {
                if (arrivals.incrementAndGet() == 2) both.complete(Unit)
                both.await()
                memory.put(key, value, ifAbsent)
            }
        }
        val requests = listOf(claimRequest(), claimRequest())
        requests.forEach { ledger.set(it) }
        val service = claimService(racing, ledger)
        val outcomes = requests.map { request -> async(Dispatchers.Default) { runCatching { service.claim(request) } } }
            .map { it.await() }
        assertEquals(1, outcomes.count { it.isSuccess })
        val error = outcomes.single { it.isFailure }.exceptionOrNull() as ContinuityClaimException
        assertEquals(ContinuityClaimFailure.ALREADY_CLAIMED, error.reason)
        assertEquals(1, memory.claims().size)
        assertEquals(0, memory.deleteCount.get())
    }

    @Test fun networkFailureAfterRemoteWriteRetriesIdenticalPendingAttempt() = runBlocking {
        val memory = ClaimMemory()
        val ledger = ClaimLedger()
        val first = AtomicBoolean(true)
        val uncertain = object : ObjectProvider by memory {
            override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) {
                memory.put(key, value, ifAbsent)
                if (first.getAndSet(false)) throw SyncFailure(SyncError.NETWORK)
            }
        }
        val request = claimRequest()
        ledger.set(request)
        val service = claimService(uncertain, ledger)
        assertReason(ContinuityClaimFailure.REMOTE_UNAVAILABLE) { service.claim(request) }
        assertEquals(1, memory.claims().size)
        assertEquals(request, service.claim(request).request)
        assertEquals(2, memory.putCount.get())
        assertEquals(0, memory.deleteCount.get())
    }

    @Test fun deadlineAfterRemoteWriteKeepsReservationAndAllowsPendingRetry() = runBlocking {
        val memory = ClaimMemory()
        val ledger = ClaimLedger()
        val first = AtomicBoolean(true)
        val slow = object : ObjectProvider by memory {
            override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) {
                memory.put(key, value, ifAbsent)
                if (first.getAndSet(false)) awaitCancellation()
            }
        }
        val request = claimRequest()
        ledger.set(request)
        val service = claimService(slow, ledger, timeout = 100)
        try {
            service.claim(request)
            fail("Expected timeout")
        } catch (_: TimeoutCancellationException) {
            assertEquals(1, memory.claims().size)
        }
        assertEquals(request, service.claim(request).request)
        assertEquals(0, memory.deleteCount.get())
    }

    @Test fun failedReadbackOrMismatchedExistingObjectNeverSucceedsOrDeletes() = runBlocking {
        val memory = ClaimMemory()
        val ledger = ClaimLedger()
        val request = claimRequest()
        ledger.set(request)
        val distorted = object : ObjectProvider by memory {
            override suspend fun get(key: String): ByteArray? = if (key.startsWith(CLAIM_NAMESPACE)) byteArrayOf(1) else memory.get(key)
        }
        val service = claimService(distorted, ledger)
        assertReason(ContinuityClaimFailure.READBACK_MISMATCH) { service.claim(request) }
        assertReason(ContinuityClaimFailure.ALREADY_CLAIMED) { service.claim(request) }
        assertEquals(1, memory.claims().size)
        assertEquals(0, memory.deleteCount.get())
    }

    @Test fun providerCannotChangeTheExpectedReadbackByMutatingPutBuffer() = runBlocking {
        val memory = ClaimMemory()
        val ledger = ClaimLedger()
        val request = claimRequest()
        ledger.set(request)
        val corrupt = object : ObjectProvider by memory {
            override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) {
                value.fill(0)
                memory.put(key, value, ifAbsent)
            }
        }
        assertReason(ContinuityClaimFailure.READBACK_MISMATCH) { claimService(corrupt, ledger).claim(request) }
        assertEquals(1, memory.claims().size)
        assertEquals(0, memory.deleteCount.get())
    }

    @Test fun changedSnapshotCannotReusePendingOperation() = runBlocking {
        val memory = ClaimMemory()
        val ledger = ClaimLedger()
        val request = claimRequest()
        ledger.set(request)
        claimService(memory, ledger).claim(request)
        val changed = request.copy(sidecarSha256 = "c".repeat(64))
        assertReason(ContinuityClaimFailure.ATTEMPT_NOT_PENDING) { claimService(memory, ledger).claim(changed) }
        ledger.set(changed)
        assertReason(ContinuityClaimFailure.ALREADY_CLAIMED) { claimService(memory, ledger).claim(changed) }
    }

    @Test fun cancelledAfterCreateLeavesReservationForTheSamePendingOperation() = runBlocking {
        val memory = ClaimMemory()
        val ledger = ClaimLedger()
        val entered = CompletableDeferred<Unit>()
        val wait = CompletableDeferred<Unit>()
        val first = AtomicBoolean(true)
        val slow = object : ObjectProvider by memory {
            override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) {
                memory.put(key, value, ifAbsent)
                if (first.getAndSet(false)) { entered.complete(Unit); wait.await() }
            }
        }
        val request = claimRequest()
        ledger.set(request)
        val service = claimService(slow, ledger)
        val job = launch { service.claim(request) }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, memory.claims().size)
        assertEquals(0, memory.deleteCount.get())
        assertEquals(request, service.claim(request).request)
    }

    @Test fun ledgerCompletionBetweenWriteAndReadbackCannotReturnReceipt() = runBlocking {
        val memory = ClaimMemory()
        val ledger = ClaimLedger()
        val request = claimRequest()
        ledger.set(request)
        val closing = object : ObjectProvider by memory {
            override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) {
                memory.put(key, value, ifAbsent)
                ledger.set(request, ContinuityClaimAttemptStatus.COMPLETED)
            }
        }
        assertReason(ContinuityClaimFailure.ATTEMPT_NOT_PENDING) { claimService(closing, ledger).claim(request) }
        assertEquals(1, memory.claims().size)
        assertEquals(0, memory.deleteCount.get())
    }
}

internal suspend fun assertReason(expected: ContinuityClaimFailure, action: suspend () -> Unit) {
    try {
        action()
        fail("Expected $expected")
    } catch (error: ContinuityClaimException) {
        assertEquals(expected, error.reason)
        assertEquals(null, error.cause)
    }
}
