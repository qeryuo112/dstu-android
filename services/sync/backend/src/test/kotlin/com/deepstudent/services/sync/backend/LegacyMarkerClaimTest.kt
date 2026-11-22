package com.deepstudent.services.sync.backend

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.sync.api.PairingMode
import com.deepstudent.services.sync.api.PairingRequest
import com.deepstudent.services.sync.api.SyncConfiguration
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.api.SyncResult
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class LegacyMarkerClaimTest {
    @Test fun trulyEmptyV1UsesFirstPasswordButUnprovenRepositoryDoesNot() = runBlocking {
        val empty = Remote()
        val own = Store("new")
        assertTrue(own.join(empty) is SyncResult.Success)
        assertEquals("instance", own.config?.instanceId)
        assertEquals(listOf(MARKER_KEY), empty.writes.filter { it == MARKER_KEY })
        val parsed = RepositoryMarker.decode(empty.marker())
        assertEquals("frozen-creator", parsed.creator)
        assertEquals("2026-01-01T00:00:00Z", parsed.createdAt)
        assertEquals(winner, parsed.verifier)
        assertFalse(empty.objects.keys.any { it.startsWith("$MARKER_KEY.") })

        val unproven = Remote().apply { objects["backups/unindexed.zip"] = byteArrayOf(1) }
        val pending = Store("other")
        assertEquals(SyncResult.Failure(SyncError.ENCRYPTION_PROOF_REQUIRED), pending.join(unproven))
        assertNull(pending.config)
        assertTrue(unproven.writes.isEmpty())
        assertArrayEquals(v1, unproven.marker())
    }

    @Test fun joinRechecksOriginalInstanceAndDoesNotReplaceForeignMarkerOrLease() = runBlocking {
        val remote = Remote()
        remote.beforeReplacement = { key -> if (key == MARKER_KEY) {
            remote.objects[MARKER_KEY] = otherMarker
            remote.objects["$MARKER_KEY.lease"] = "foreign-lease".toByteArray()
        } }
        val own = Store("first")
        assertEquals(SyncResult.Failure(SyncError.ENCRYPTION_CLAIM_CONFLICT), own.join(remote))
        assertNull(own.config)
        assertArrayEquals(otherMarker, remote.marker())
        assertArrayEquals("foreign-lease".toByteArray(), remote.objects["$MARKER_KEY.lease"])
        assertTrue(remote.objects.keys.none { it.startsWith("$MARKER_KEY.pending/") })

        val switched = Remote()
        switched.beforePut = { key -> if (key == "$MARKER_KEY.lease") {
            switched.objects[PairingService.INSTANCE_KEY] = """{"instance_id":"other"}""".toByteArray()
        } }
        assertEquals(SyncResult.Failure(SyncError.INVALID_REPOSITORY), own.join(switched))
        assertNull(own.config)
        assertArrayEquals(v1, switched.marker())
    }

    @Test fun racingPairingCannotPublishWhileAnotherClaimIsPaused() = runBlocking {
        val remote = Remote()
        val a = Store("a")
        val b = Store("b")
        val paused = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        remote.beforeReplacement = { key -> if (key == MARKER_KEY) { paused.complete(Unit); resume.await() } }
        val first = async { a.join(remote) }
        withTimeout(5_000) { paused.await() }
        try {
            assertEquals(SyncResult.Failure(SyncError.ENCRYPTION_CLAIM_CONFLICT), b.join(remote, "different"))
            assertNull(b.config)
            assertArrayEquals(v1, remote.marker())
        } finally { resume.complete(Unit) }
        assertTrue(first.await() is SyncResult.Success)
        assertEquals(1, remote.writes.count { it == MARKER_KEY })
        assertEquals(winner, RepositoryMarker.decode(remote.marker()).verifier)
    }

    @Test fun cancellationBeforeWriteOnlyRemovesOwnedClaimAndAllowsRetry() = runBlocking {
        val remote = Remote()
        val store = Store("a")
        val reached = CompletableDeferred<Unit>()
        remote.beforePut = { key -> if (key == "$MARKER_KEY.lease") {
            reached.complete(Unit)
            CompletableDeferred<Unit>().await()
        } }
        val request = request("correct")
        val job = launch { store.service(remote).pair(PairingMode.JOIN, request) }
        withTimeout(5_000) { reached.await() }
        job.cancelAndJoin()
        assertTrue(request.password.all { it == '\u0000' } && request.encryptionPassword!!.all { it == '\u0000' })
        assertNull(store.config)
        assertArrayEquals(v1, remote.marker())
        assertFalse(remote.objects.keys.any { it.startsWith("$MARKER_KEY.") })
        remote.beforePut = {}
        assertTrue(store.join(remote) is SyncResult.Success)
    }

    @Test fun lostWriteResponseKeepsEvidenceAndNeverSavesUntilV2IsVerifiedOnRetry() = runBlocking {
        val remote = Remote().apply { loseMarkerResponse = true }
        val store = Store("a")
        assertEquals(SyncResult.Failure(SyncError.ENCRYPTION_UPGRADE_UNCERTAIN), store.join(remote))
        assertNull(store.config)
        val installed = remote.marker().copyOf()
        assertEquals(winner, RepositoryMarker.decode(installed).verifier)
        assertTrue(remote.objects.keys.any { it.startsWith("$MARKER_KEY.pending/") })
        assertEquals(SyncResult.Failure(SyncError.ENCRYPTION_PASSWORD_INCORRECT), store.join(remote, "different"))
        assertTrue(store.join(remote) is SyncResult.Success)
        assertArrayEquals(installed, remote.marker())
        assertEquals(1, remote.writes.count { it == MARKER_KEY })
    }

    @Test fun desktopClaimShapeBlocksLiveLeaseAndOnlyReclaimsKnownExpiredLease() = runBlocking {
        val remote = Remote()
        val now = Instant.parse("2026-01-01T00:00:10Z")
        val lease = """{"format_version":1,"device_id":"desktop","nonce":"c42c22d3-9811-418e-abc0-4320f3f60e47","created_at":"2026-01-01T00:00:00Z","expires_at":"2026-01-01T00:01:00Z"}""".toByteArray()
        remote.objects["$MARKER_KEY.lease"] = lease
        val failure = assertThrows(SyncFailure::class.java) {
            runBlocking { LegacyMarkerClaim(remote, Clock.fixed(now, ZoneOffset.UTC)).upgrade(v1, otherMarker, "a") {} }
        }
        assertEquals(SyncError.ENCRYPTION_CLAIM_CONFLICT, failure.error)
        assertArrayEquals(lease, remote.objects["$MARKER_KEY.lease"])
        assertArrayEquals(v1, remote.marker())
        val later = Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC)
        LegacyMarkerClaim(remote, later).upgrade(v1, otherMarker, "a") {}
        assertArrayEquals(otherMarker, remote.marker())
        assertFalse(remote.objects.keys.any { it.startsWith("$MARKER_KEY.") })
    }

    private class Remote : ObjectProvider {
        val objects = mutableMapOf(MARKER_KEY to v1, PairingService.FORMAT_KEY to PairingService.FORMAT.toByteArray(),
            PairingService.INSTANCE_KEY to """{"instance_id":"instance","format_version":4}""".toByteArray())
        val writes = mutableListOf<String>()
        var beforePut: suspend (String) -> Unit = {}
        var beforeReplacement: suspend (String) -> Unit = {}
        var loseMarkerResponse = false
        fun marker() = objects.getValue(MARKER_KEY)
        override suspend fun get(key: String) = objects[key]?.copyOf()
        override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) {
            beforePut(key)
            if (ifAbsent && key in objects) throw SyncFailure(SyncError.ALREADY_EXISTS)
            objects[key] = value.copyOf()
            writes.add(key)
            if (key == MARKER_KEY && loseMarkerResponse) throw SyncFailure(SyncError.NETWORK)
        }
        override suspend fun replaceClaimed(key: String, observed: ByteArray, value: ByteArray): Boolean {
            beforeReplacement(key)
            return super.replaceClaimed(key, observed, value)
        }
        override suspend fun delete(key: String) { objects.remove(key) }
        override suspend fun list(prefix: String) = objects.keys.filter { it.startsWith(prefix) }.sorted()
    }

    private class Store(private val device: String) : ConfigurationStore, DeviceIdentity {
        var config: SyncConfiguration? = null
        override fun getOrCreate() = device
        override fun read() = config
        override fun save(configuration: SyncConfiguration, password: CharArray, encryptionPassword: CharArray?) { config = configuration }
        override fun replaceEncryptionPassword(configuration: SyncConfiguration, password: CharArray) = error("not used")
        override fun clear() { config = null }
        fun service(remote: ObjectProvider) = PairingService(this, this, object : ProviderFactory {
            override fun open(endpoint: String, username: String, root: String, password: CharArray) = remote
        }, encryption = RepositoryEncryption(Crypto))
        suspend fun join(remote: ObjectProvider, password: String = "correct") = service(remote).pair(PairingMode.JOIN, request(password))
    }

    private object Crypto : SyncCryptography {
        override fun createVerifier(password: CharArray) = if (password.contentEquals("correct".toCharArray())) winner else loser
        override fun verify(password: CharArray, verifier: SyncPasswordVerifier) = createVerifier(password) == verifier
        override fun payload(password: CharArray): SyncPayloadCodec = error("not used")
    }

    private companion object {
        private val v1 = """{"version":1,"createdByDevice":"frozen-creator","createdAt":"2026-01-01T00:00:00Z"}""".toByteArray()
        private val winner = SyncPasswordVerifier(64, 1, 1, "00".repeat(8), "11".repeat(32))
        private val loser = winner.copy(digestHex = "22".repeat(32))
        private val otherMarker = RepositoryMarker.decode(v1).encodeV2(loser)
        private fun request(password: String) = PairingRequest("https://example.invalid", "u", "r", "dav".toCharArray(), password.toCharArray())
    }
}
