package com.deepstudent.services.sync.backend

import com.deepstudent.services.sync.backend.transfer.ContinuityWriterReadback
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuityWriterReadbackTest {
    private fun key(writer: String = TEST_WRITER, sequence: Long = 5, operation: String = "one") =
        SyncWireCodec.changeKey(writer, sequence.toULong(), 1700000000u, operation, true)

    @Test fun duplicateWriterSequenceIsRejectedBeforeConditionalCreate() = runBlocking {
        val memory = ClaimMemory()
        memory.objects[key(operation = "one")] = byteArrayOf(1)
        memory.objects[key(operation = "two")] = byteArrayOf(2)
        val ledger = ClaimLedger()
        val request = claimRequest().also { ledger.set(it) }
        var intent = false
        val service = service(memory, ledger) { intent = true }
        assertReason(ContinuityClaimFailure.WRITER_SEQUENCE_CONFLICT) { service.claim(request) }
        assertEquals(0, memory.putCount.get())
        assertTrue(memory.claims().isEmpty())
        assertEquals(false, intent)
    }

    @Test fun conflictIntroducedAfterTheConditionalWriteFailsRevalidationWithoutDeletingClaim() = runBlocking {
        val memory = ClaimMemory()
        memory.objects[key()] = byteArrayOf(1)
        val ledger = ClaimLedger()
        val request = claimRequest().also { ledger.set(it) }
        var intent = false
        val concurrent = object : ObjectProvider by memory {
            override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) {
                memory.put(key, value, ifAbsent)
                memory.objects[this@ContinuityWriterReadbackTest.key(operation = "two")] = byteArrayOf(2)
            }
        }
        val service = service(concurrent, ledger) { intent = true }
        assertReason(ContinuityClaimFailure.WRITER_SEQUENCE_CONFLICT) { service.claim(request) }
        assertTrue(intent)
        assertEquals(1, memory.claims().size)
        assertEquals(0, memory.deleteCount.get())
    }

    @Test fun otherWritersAndRepeatedIdenticalListingKeysDoNotCreateAConflict() = runBlocking {
        val memory = ClaimMemory()
        memory.objects[key()] = byteArrayOf(1)
        memory.objects[key(writer = "other-writer", operation = "other")] = byteArrayOf(2)
        val repeated = object : ObjectProvider by memory {
            override suspend fun list(prefix: String) = memory.list(prefix).let { it + it }
        }
        assertEquals(5L, ContinuityWriterReadback.sequence(repeated, PlainSyncPayload, TEST_WRITER))
    }

    private fun service(provider: ObjectProvider, ledger: ClaimLedger, beforeCreate: () -> Unit) = ContinuityRemoteClaim(
        provider, claimConditionalProof,
        retirement = { request ->
            val sequence = ContinuityWriterReadback.sequence(provider, PlainSyncPayload, request.writerId)
            if (sequence != 5L) throw ContinuityClaimException(ContinuityClaimFailure.WRITER_SEQUENCE_CHANGED)
            ContinuityClaimRetirementProof(request)
        },
        ledger = ledger,
        beforeConditionalCreate = { beforeCreate() },
    )
}
