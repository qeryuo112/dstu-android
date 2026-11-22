package com.deepstudent.services.sync.backend

import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ContinuityClaimSafetyTest {
    @Test fun wrongRemoteInstanceNeverReachesProofsOrWrites() = runBlocking {
        val memory = ClaimMemory()
        val ledger = ClaimLedger()
        val request = claimRequest(instance = "wrong-repository")
        ledger.set(request)
        val calls = AtomicInteger()
        val conditional = ContinuityClaimConditionalCreateVerifier { provider, instance ->
            calls.incrementAndGet()
            ContinuityClaimConditionalCreateProof(provider, instance)
        }
        assertReason(ContinuityClaimFailure.REMOTE_UNAVAILABLE) { claimService(memory, ledger, conditional).claim(request) }
        assertEquals(0, calls.get())
        assertEquals(0, memory.putCount.get())
        assertTrue(memory.claims().isEmpty())
    }

    @Test fun unsupportedConditionalCreateAndWrongProofBindingFailClosed() = runBlocking {
        val memory = ClaimMemory()
        val ledger = ClaimLedger()
        val request = claimRequest()
        ledger.set(request)
        val noProof = ContinuityClaimConditionalCreateVerifier { _, _ -> null }
        assertReason(ContinuityClaimFailure.CONDITIONAL_CREATE_UNVERIFIED) { claimService(memory, ledger, noProof).claim(request) }
        val wrongProvider = ContinuityClaimConditionalCreateVerifier { _, instance ->
            ContinuityClaimConditionalCreateProof(ClaimMemory(), instance)
        }
        assertReason(ContinuityClaimFailure.CONDITIONAL_CREATE_UNVERIFIED) { claimService(memory, ledger, wrongProvider).claim(request) }
        val wrongInstance = ContinuityClaimConditionalCreateVerifier { provider, _ ->
            ContinuityClaimConditionalCreateProof(provider, "another-instance")
        }
        assertReason(ContinuityClaimFailure.CONDITIONAL_CREATE_UNVERIFIED) { claimService(memory, ledger, wrongInstance).claim(request) }
        assertEquals(0, memory.putCount.get())
        assertTrue(memory.claims().isEmpty())
    }

    @Test fun atomicGuaranteeMustStillBeVerifiedAfterWrite() = runBlocking {
        val memory = ClaimMemory()
        val ledger = ClaimLedger()
        val request = claimRequest()
        ledger.set(request)
        val calls = AtomicInteger()
        val revoked = ContinuityClaimConditionalCreateVerifier { provider, instance ->
            if (calls.incrementAndGet() == 1) ContinuityClaimConditionalCreateProof(provider, instance) else null
        }
        assertReason(ContinuityClaimFailure.CONDITIONAL_CREATE_UNVERIFIED) {
            claimService(memory, ledger, conditional = revoked).claim(request)
        }
        assertEquals(1, memory.claims().size)
        assertEquals(0, memory.deleteCount.get())
    }

    @Test fun missingOrWrongRetirementProofCannotReserve() = runBlocking {
        val memory = ClaimMemory()
        val ledger = ClaimLedger()
        val request = claimRequest()
        ledger.set(request)
        val noProof = ContinuityClaimRetirementVerifier { null }
        assertReason(ContinuityClaimFailure.WRITER_NOT_RETIRED) {
            claimService(memory, ledger, retirement = noProof).claim(request)
        }
        val wrongWriter = ContinuityClaimRetirementVerifier { binding -> ContinuityClaimRetirementProof(binding.copy(writerId = "other")) }
        assertReason(ContinuityClaimFailure.WRITER_NOT_RETIRED) {
            claimService(memory, ledger, retirement = wrongWriter).claim(request)
        }
        val wrongInstance = ContinuityClaimRetirementVerifier { binding -> ContinuityClaimRetirementProof(binding.copy(instanceId = "other")) }
        assertReason(ContinuityClaimFailure.WRITER_NOT_RETIRED) {
            claimService(memory, ledger, retirement = wrongInstance).claim(request)
        }
        val wrongSnapshot = ContinuityClaimRetirementVerifier { binding ->
            ContinuityClaimRetirementProof(binding.copy(archiveSha256 = "c".repeat(64)))
        }
        assertReason(ContinuityClaimFailure.WRITER_NOT_RETIRED) {
            claimService(memory, ledger, retirement = wrongSnapshot).claim(request)
        }
        assertEquals(0, memory.putCount.get())
    }

    @Test fun retirementMustRemainVerifiedAfterTheWrite() = runBlocking {
        val memory = ClaimMemory()
        val ledger = ClaimLedger()
        val request = claimRequest()
        ledger.set(request)
        val calls = AtomicInteger()
        val revoked = ContinuityClaimRetirementVerifier { binding ->
            if (calls.incrementAndGet() == 1) ContinuityClaimRetirementProof(binding) else null
        }
        assertReason(ContinuityClaimFailure.WRITER_NOT_RETIRED) {
            claimService(memory, ledger, retirement = revoked).claim(request)
        }
        assertEquals(1, memory.claims().size)
        assertEquals(0, memory.deleteCount.get())
    }

    @Test fun remoteInstanceMustStillMatchAfterTheWrite() = runBlocking {
        val memory = ClaimMemory()
        val ledger = ClaimLedger()
        val request = claimRequest()
        ledger.set(request)
        val changed = object : ObjectProvider by memory {
            override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) {
                memory.put(key, value, ifAbsent)
                memory.objects[PairingService.INSTANCE_KEY] = """{"format_version":4,"instance_id":"other"}""".toByteArray()
            }
        }
        assertReason(ContinuityClaimFailure.REMOTE_UNAVAILABLE) { claimService(changed, ledger).claim(request) }
        assertEquals(1, memory.claims().size)
        assertEquals(0, memory.deleteCount.get())
    }

    @Test fun inputIsBoundedAndInstallationAndOperationIdsAreCanonicalUuids() = runBlocking {
        val memory = ClaimMemory()
        val ledger = ClaimLedger()
        val request = claimRequest()
        val invalid = listOf(
            request.copy(instanceId = ""), request.copy(writerId = " "), request.copy(writerId = "a\u0000b"),
            request.copy(writerId = "a".repeat(257)), request.copy(writerId = "界".repeat(86)),
            request.copy(writerId = "\uD800"), request.copy(instanceId = " default"),
            request.copy(targetInstallationId = "../../install"), request.copy(operationId = "../operation"),
            request.copy(operationId = "00000000-0000-0000-0000-000000000000"),
            request.copy(targetInstallationId = "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"),
            request.copy(archiveSha256 = "a".repeat(63)), request.copy(sidecarSha256 = "z".repeat(64)),
            request.copy(sidecarSha256 = "b".repeat(65)),
        )
        val service = claimService(memory, ledger)
        invalid.forEach { bad -> assertReason(ContinuityClaimFailure.INVALID_REQUEST) { service.claim(bad) } }
        assertEquals(0, memory.putCount.get())
    }

    @Test fun legacyAndPathShapedWriterIdsAreEncodedAsHashNotPath() = runBlocking {
        val memory = ClaimMemory()
        val ledger = ClaimLedger()
        val request = claimRequest(writer = "../device/with spaces")
        ledger.set(request)
        claimService(memory, ledger).claim(request)
        val key = memory.claims().keys.single()
        assertTrue(key.matches(Regex("dstu-management/continuity-claims/v1/[a-f0-9]{64}\\.claim")))
        assertFalse(key.contains(".."))
        assertFalse(memory.claims().values.single().toString(Charsets.UTF_8).contains(request.writerId))
    }

    @Test fun distinctFramedIdentityPairsCannotAliasTheSameClaimPath() = runBlocking {
        val memory = ClaimMemory("ab")
        val ledger = ClaimLedger()
        val first = claimRequest(instance = "ab", writer = "c")
        ledger.set(first)
        claimService(memory, ledger).claim(first)
        memory.objects[PairingService.INSTANCE_KEY] = """{"format_version":4,"instance_id":"a"}""".toByteArray()
        val second = claimRequest(instance = "a", writer = "bc")
        ledger.set(second)
        claimService(memory, ledger).claim(second)
        assertEquals(2, memory.claims().size)
    }

    @Test fun timeoutAndCancellationPropagateWithoutCreatingOrDeletingClaims() = runBlocking {
        val memory = ClaimMemory()
        val ledger = ClaimLedger()
        val request = claimRequest()
        ledger.set(request)
        val calls = AtomicInteger()
        val enteredAgain = CompletableDeferred<Unit>()
        val suspended = object : ObjectProvider by memory {
            override suspend fun get(key: String): ByteArray? {
                if (calls.incrementAndGet() == 2) enteredAgain.complete(Unit)
                awaitCancellation()
            }
        }
        val service = claimService(suspended, ledger, timeout = 50)
        try {
            service.claim(request)
            fail("Expected timeout")
        } catch (_: CancellationException) {
            assertEquals(0, memory.putCount.get())
        }
        val noTimeout = claimService(suspended, ledger)
        val job = launch { noTimeout.claim(request) }
        enteredAgain.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(memory.claims().isEmpty())
        assertEquals(0, memory.deleteCount.get())
    }

    @Test fun logsAndWrappedDependencyErrorsDoNotRevealCredentials() = runBlocking {
        val memory = ClaimMemory()
        val ledger = ClaimLedger()
        val request = claimRequest(writer = "sensitive-writer")
        ledger.set(request)
        val logs = Collections.synchronizedList(mutableListOf<LogRecord>())
        val logger = object : Logger { override fun log(record: LogRecord) { logs.add(record) } }
        val secret = "super-secret-password"
        val broken = object : ObjectProvider by memory {
            override suspend fun get(key: String): ByteArray? = throw IOException(secret)
        }
        try {
            claimService(broken, ledger, logger = logger).claim(request)
            fail("Expected error")
        } catch (failure: ContinuityClaimException) {
            assertEquals(ContinuityClaimFailure.DEPENDENCY_UNAVAILABLE, failure.reason)
            assertFalse(failure.toString().contains(secret))
            assertEquals(null, failure.cause)
        }
        val output = logs.joinToString("\n")
        assertFalse(output.contains(secret))
        assertFalse(output.contains(request.instanceId))
        assertFalse(output.contains(request.writerId))
        assertTrue(output.contains("sync.continuity_claim"))
        // This locally generated opaque operation UUID is the trace correlation, not a writer or secret.
        assertTrue(logs.isNotEmpty() && logs.all { it.correlationId?.value == request.operationId })
        assertEquals("ContinuityClaimRequest(redacted)", request.toString())
    }
}
