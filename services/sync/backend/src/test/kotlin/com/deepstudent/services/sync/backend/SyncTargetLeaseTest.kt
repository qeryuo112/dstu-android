package com.deepstudent.services.sync.backend

import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.transfer.SyncTargetLease
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncTargetLeaseTest {
    private val prefix = "data_governance/locks/sync-target/"
    private val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private class Provider : ObjectProvider {
        val values = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        var listedMissing = false
        var alterRead = false
        var deleteCount = 0
        override suspend fun get(key: String): ByteArray? = values[key]?.let { if (alterRead) byteArrayOf(1) else it.copyOf() }
        override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) {
            if (ifAbsent && values.putIfAbsent(key, value.copyOf()) != null) throw SyncFailure(SyncError.ALREADY_EXISTS)
            if (!ifAbsent) values[key] = value.copyOf()
        }
        override suspend fun list(prefix: String): List<String> = values.keys.filter { it.startsWith(prefix) } +
            if (listedMissing) listOf(prefix + "missing.json") else emptyList()
        override suspend fun delete(key: String) { deleteCount++; values.remove(key) }
    }
    private fun lease(provider: Provider) = SyncTargetLease(provider, "device", scope, clock)
    private fun record(id: String, expires: String) = """{"format_version":1,"target":"data_governance","holder_device_id":"peer","operation_id":"$id","created_at":"2025-01-01T00:00:00Z","expires_at":"$expires","activation_committed":true}""".toByteArray()

    @org.junit.After fun cleanup() { scope.cancel() }

    @Test fun sameDeviceCannotAcquireAndReleaseOnlyOwn() = runBlocking {
        val provider = Provider()
        val handle = lease(provider).acquire()
        val failure = assertThrows(SyncFailure::class.java) { runBlocking { lease(provider).acquire() } }
        assertEquals(SyncError.ALREADY_EXISTS, failure.error)
        handle.guarded().put("object", byteArrayOf(1))
        handle.release()
        assertTrue(provider.values.keys.none { it.startsWith(prefix) })
        assertThrows(SyncFailure::class.java) { handle.requireHealthy() }
        Unit
    }

    @Test fun expiredReclaimedButCorruptAndMissingFailClosed() = runBlocking {
        val provider = Provider()
        val id = UUID.randomUUID().toString()
        provider.values[prefix + id + ".json"] = record(id, "2025-12-31T00:00:00Z")
        val handle = lease(provider).acquire()
        assertEquals(1, provider.deleteCount)
        handle.release()
        provider.values[prefix + id + ".json"] = byteArrayOf(1)
        assertThrows(SyncFailure::class.java) { runBlocking { lease(provider).acquire() } }
        assertEquals(2, provider.deleteCount)
        provider.values.clear()
        provider.listedMissing = true
        assertThrows(SyncFailure::class.java) { runBlocking { lease(provider).acquire() } }
        Unit
    }

    @Test fun changedReadbackRejectedWithoutDeletingUnownedObject() {
        val provider = Provider().apply { alterRead = true }
        assertThrows(SyncFailure::class.java) { runBlocking { lease(provider).acquire() } }
        assertEquals(0, provider.deleteCount)
    }

    @Test fun releaseDoesNotDeleteAlteredOwnership() = runBlocking {
        val provider = Provider()
        val handle = lease(provider).acquire()
        val key = provider.values.keys.single()
        val other = UUID.randomUUID().toString()
        provider.values[key] = record(other, "2027-01-01T00:00:00Z")
        assertThrows(SyncFailure::class.java) { runBlocking { handle.release() } }
        assertTrue(provider.values.containsKey(key))
    }

    @Test fun racingContendersElectOneWinner() = runBlocking {
        val provider = Provider()
        val firstScan = java.util.concurrent.atomic.AtomicInteger()
        val bothListed = CompletableDeferred<Unit>()
        val bothWritten = CompletableDeferred<Unit>()
        val writes = java.util.concurrent.atomic.AtomicInteger()
        val racing = object : ObjectProvider by provider {
            override suspend fun list(prefix: String): List<String> {
                if (firstScan.incrementAndGet() <= 2) {
                    if (firstScan.get() == 2) bothListed.complete(Unit)
                    bothListed.await()
                    return emptyList()
                }
                return provider.list(prefix)
            }
            override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) {
                provider.put(key, value, ifAbsent)
                if (ifAbsent) {
                    if (writes.incrementAndGet() == 2) bothWritten.complete(Unit)
                    bothWritten.await()
                }
            }
        }
        val results = listOf("a", "b").map { id -> async(Dispatchers.Default) {
            runCatching { SyncTargetLease(racing, id, scope, clock).acquire() }
        } }.map { it.await() }
        assertTrue(results.count { it.isSuccess } <= 1)
        assertTrue(results.any { it.isFailure })
        results.firstOrNull { it.isSuccess }?.getOrThrow()?.release()
        Unit
    }

    @Test fun cancelledAcquireCleansOwnedCandidate() = runBlocking {
        val provider = Provider()
        val written = CompletableDeferred<Unit>()
        val blocker = CompletableDeferred<Unit>()
        val slow = object : ObjectProvider by provider {
            override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) {
                provider.put(key, value, ifAbsent)
                if (ifAbsent) { written.complete(Unit); blocker.await() }
            }
        }
        val job = launch { SyncTargetLease(slow, "device", scope, clock).acquire() }
        written.await()
        job.cancel()
        job.join()
        assertTrue(provider.values.isEmpty())
    }

    @Test fun heartbeatLossBlocksWrites() = runBlocking {
        val provider = Provider()
        val wake = CompletableDeferred<Unit>()
        val handle = SyncTargetLease(provider, "device", scope, clock, 300) { wake.await() }.acquire()
        provider.values.clear()
        wake.complete(Unit)
        withTimeout(3000) {
            while (true) {
                try { handle.requireHealthy(); kotlinx.coroutines.delay(5) } catch (_: SyncFailure) { break }
            }
        }
        assertThrows(SyncFailure::class.java) { runBlocking { handle.guarded().put("object", byteArrayOf(1)) } }
        assertTrue(!provider.values.containsKey("object"))
        assertThrows(SyncFailure::class.java) { runBlocking { handle.release() } }
        Unit
    }
}
