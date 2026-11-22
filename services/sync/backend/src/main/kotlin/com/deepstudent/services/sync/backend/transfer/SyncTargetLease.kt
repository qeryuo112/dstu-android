package com.deepstudent.services.sync.backend.transfer

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.ObjectProvider
import com.deepstudent.services.sync.backend.SyncFailure
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val PREFIX = "data_governance/locks/sync-target/"
private const val TARGET = "data_governance"
private const val CLEANUP_TIMEOUT = 5_000L

class SyncTargetLease(
    private val provider: ObjectProvider,
    private val deviceId: String,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val ttlMillis: Long = 600_000,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    init { require(deviceId.isNotBlank() && ttlMillis >= 3) }

    suspend fun acquire(): LeaseHandle {
        if (scan().isNotEmpty()) fail(SyncError.ALREADY_EXISTS)
        val now = clock.instant()
        val record = LeaseRecord(deviceId.trim(), UUID.randomUUID().toString(), now, now.plusMillis(ttlMillis), false)
        var attempted = false
        try {
            attempted = true
            putVerified(provider, record, true)
            requireWinner(record, false)
            val committed = record.copy(committed = true)
            putVerified(provider, committed, false)
            requireWinner(committed, true)
            return LeaseHandle(provider, committed, clock, ttlMillis, scope, sleep)
        } catch (error: Throwable) {
            if (attempted) cleanupCandidate(provider, record.operation, error)
            throw error
        }
    }

    private suspend fun requireWinner(record: LeaseRecord, committed: Boolean) {
        val winner = scan().minWithOrNull(compareBy<LeaseRecord>({ it.created }, { it.operation }))
        if (winner?.operation != record.operation || winner.committed != committed) fail(SyncError.ALREADY_EXISTS)
    }

    private suspend fun scan(): List<LeaseRecord> {
        val keys = leaseIo { provider.list(PREFIX) }.filter { it.startsWith(PREFIX) && it.endsWith(".json") }.sorted()
        val active = mutableListOf<LeaseRecord>()
        for (key in keys) {
            val bytes = leaseIo { provider.get(key) } ?: fail(SyncError.NETWORK)
            val record = decodeLease(bytes, key)
            if (record.expires > clock.instant()) active.add(record)
            else {
                val current = leaseIo { provider.get(key) } ?: fail(SyncError.NETWORK)
                if (!current.contentEquals(bytes)) fail(SyncError.ALREADY_EXISTS)
                leaseIo { provider.delete(key) }
            }
        }
        return active
    }
}

class LeaseHandle internal constructor(
    private val provider: ObjectProvider,
    record: LeaseRecord,
    private val clock: Clock,
    private val ttlMillis: Long,
    scope: CoroutineScope,
    private val sleep: suspend (Long) -> Unit,
) {
    private val current = AtomicReference(record)
    private val failure = AtomicReference<SyncError?>(null)
    private val heartbeat: Job = scope.launch {
        try {
            while (true) {
                sleep(ttlMillis / 3)
                renew()
            }
        } catch (cancelled: CancellationException) {
            if (currentCoroutineContext().isActive) failure.compareAndSet(null, SyncError.NETWORK)
        } catch (error: SyncFailure) {
            failure.compareAndSet(null, error.error)
        } catch (_: Exception) {
            failure.compareAndSet(null, SyncError.NETWORK)
        }
    }

    fun requireHealthy() {
        failure.get()?.let(::fail)
        if (!heartbeat.isActive || !clock.instant().isBefore(current.get().expires)) fail(SyncError.ALREADY_EXISTS)
    }

    fun guarded(): ObjectProvider = LeaseCheckedProvider(provider, this)

    suspend fun release() = withContext(NonCancellable) {
        withTimeout(CLEANUP_TIMEOUT) {
            heartbeat.cancelAndJoin()
            releaseCandidate(provider, current.get().operation)
        }
        failure.get()?.let(::fail)
    }

    private suspend fun renew() {
        requireHealthy()
        val previous = current.get()
        val raw = leaseIo { provider.get(leaseKey(previous.operation)) } ?: fail(SyncError.ALREADY_EXISTS)
        val observed = decodeLease(raw, leaseKey(previous.operation))
        if (observed != previous || !observed.committed || !clock.instant().isBefore(observed.expires)) fail(SyncError.ALREADY_EXISTS)
        val next = observed.copy(expires = clock.instant().plusMillis(ttlMillis))
        putVerified(provider, next, false)
        current.set(next)
    }
}

private class LeaseCheckedProvider(private val delegate: ObjectProvider, private val handle: LeaseHandle) : ObjectProvider {
    override suspend fun get(key: String): ByteArray? = checked { delegate.get(key) }
    override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) = checked { delegate.put(key, value, ifAbsent) }
    override suspend fun list(prefix: String): List<String> = checked { delegate.list(prefix) }
    override suspend fun delete(key: String) = checked { delegate.delete(key) }
    private suspend fun <T> checked(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        handle.requireHealthy()
        val value = block()
        currentCoroutineContext().ensureActive()
        handle.requireHealthy()
        return value
    }
}

internal data class LeaseRecord(val device: String, val operation: String, val created: Instant, val expires: Instant, val committed: Boolean)

private fun leaseKey(operation: String) = "$PREFIX$operation.json"
private fun fail(error: SyncError): Nothing = throw SyncFailure(error)

private fun encodeLease(record: LeaseRecord): ByteArray = JsonValue.Object.Builder()
    .put("format_version", 1).put("target", TARGET).put("holder_device_id", record.device)
    .put("operation_id", record.operation).put("created_at", record.created.toString())
    .put("expires_at", record.expires.toString()).put("activation_committed", record.committed)
    .build().literal().toByteArray()

private fun decodeLease(bytes: ByteArray, key: String): LeaseRecord = try {
    require(bytes.size <= 65536)
    val root = JsonValue.parseObject(bytes.decodeToString(throwOnInvalidSequence = true))
    fun text(name: String) = (root[name] as? JsonValue.Text)?.value ?: error("invalid")
    require((root["format_version"] as? JsonValue.Number)?.asLongOrNull() == 1L && text("target") == TARGET)
    val operation = text("operation_id")
    require(UUID.fromString(operation).toString() == operation && leaseKey(operation) == key)
    val device = text("holder_device_id").also { require(it.isNotBlank()) }
    LeaseRecord(device, operation, Instant.parse(text("created_at")), Instant.parse(text("expires_at")), leaseCommitted(root))
} catch (_: Exception) { fail(SyncError.INVALID_REPOSITORY) }

private fun leaseCommitted(root: JsonValue.Object): Boolean = when (val value = root["activation_committed"]) {
    null -> false
    is JsonValue.Bool -> value.value
    else -> error("invalid")
}

private suspend fun putVerified(provider: ObjectProvider, record: LeaseRecord, ifAbsent: Boolean) {
    val key = leaseKey(record.operation)
    val bytes = encodeLease(record)
    leaseIo { provider.put(key, bytes, ifAbsent) }
    val readback = leaseIo { provider.get(key) } ?: fail(SyncError.NETWORK)
    if (!readback.contentEquals(bytes)) fail(SyncError.ALREADY_EXISTS)
}

private suspend fun releaseCandidate(provider: ObjectProvider, operation: String) {
    val key = leaseKey(operation)
    val bytes = leaseIo { provider.get(key) } ?: return
    val record = decodeLease(bytes, key)
    if (record.operation != operation) fail(SyncError.ALREADY_EXISTS)
    leaseIo { provider.delete(key) }
}

private suspend fun cleanupCandidate(provider: ObjectProvider, operation: String, original: Throwable) {
    try {
        withContext(NonCancellable) { withTimeout(CLEANUP_TIMEOUT) { releaseCandidate(provider, operation) } }
    } catch (cleanup: Throwable) { original.addSuppressed(cleanup) }
}

private suspend fun <T> leaseIo(block: suspend () -> T): T {
    currentCoroutineContext().ensureActive()
    val result = try { block() } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: SyncFailure) { throw failure }
    catch (_: Exception) { fail(SyncError.NETWORK) }
    currentCoroutineContext().ensureActive()
    return result
}
