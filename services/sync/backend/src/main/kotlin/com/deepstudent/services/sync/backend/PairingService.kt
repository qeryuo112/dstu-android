package com.deepstudent.services.sync.backend

import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.services.sync.api.PairingMode
import com.deepstudent.services.sync.api.PairingRequest
import com.deepstudent.services.sync.api.SyncConfiguration
import com.deepstudent.services.sync.api.SyncConfigurationService
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.api.SyncResult
import com.deepstudent.services.sync.backend.transfer.RemoteGate
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PairingService(
    private val store: ConfigurationStore,
    private val identity: DeviceIdentity,
    private val providers: ProviderFactory,
    private val logger: Logger = NoopLogger,
    private val encryption: RepositoryEncryption = RepositoryEncryption(),
    private val openStored: ((SyncConfiguration) -> ObjectProvider)? = null,
    private val keyRecovery: PairingKeyRecovery? = null,
) : SyncConfigurationService {
    private val mutex = Mutex()

    override suspend fun current(): SyncResult<SyncConfiguration?> = result("read") {
        keyRecovery?.requireUsable()
        store.read()?.also(store::verifyCredentialReferences)
    }

    override suspend fun clear(): SyncResult<Unit> = result("clear") {
        keyRecovery?.requireUsable()
        store.clear()
    }

    override suspend fun resetCredentialsAfterKeyLoss(): SyncResult<Unit> = result("reset-lost-key") {
        val recovery = keyRecovery ?: throw SyncFailure(SyncError.CREDENTIAL_UNAVAILABLE)
        // A failed key rebuild leaves the binding in place. If the process is interrupted after
        // rebuilding but before clear, the next read rejects the now-dangling credential refs.
        recovery.rebuildLostKey()
        store.clear()
    }

    override suspend fun pair(mode: PairingMode, request: PairingRequest): SyncResult<SyncConfiguration> = try {
        result("pair") { pairInternal(mode, request) }
    } finally {
        request.clearSecrets()
    }

    override suspend fun updateEncryptionPassword(password: CharArray): SyncResult<Unit> = try {
        result("update-encryption-password") {
            keyRecovery?.requireUsable()
            val config = store.read()?.takeIf { it.encrypted } ?: throw SyncFailure(SyncError.INVALID_CONFIGURATION)
            val provider = openStored?.invoke(config) ?: throw SyncFailure(SyncError.CREDENTIAL_UNAVAILABLE)
            val checked = encryption.inspect(provider, true, password)
            RemoteGate(provider, config.instanceId).minimumReader()
            val policy = finishJoinPolicy(provider, checked, config.instanceId, password) { config.deviceId }
            if (checked.legacy != null) RemoteGate(provider, config.instanceId).minimumReader()
            policy.requireUnchanged(provider)
            currentCoroutineContext().ensureActive()
            store.replaceEncryptionPassword(config, password)
        }
    } finally {
        password.fill('\u0000')
    }

    private suspend fun pairInternal(mode: PairingMode, request: PairingRequest): SyncConfiguration {
        // CREATE can mutate the repository; never start it with a known-unusable local key.
        keyRecovery?.requireUsable()
        store.requirePairingTarget(mode, request.endpoint, request.root)
        val provider = providers.open(request.endpoint, request.username, request.root, request.password)
        val password = request.encryptionPassword?.takeIf { it.isNotEmpty() }
        val checked = if (mode == PairingMode.JOIN) encryption.inspect(provider, password != null, password) else null
        val descriptor = provider.get(FORMAT_KEY)
        val instance = provider.get(INSTANCE_KEY)
        val instanceId = if (checked == null) create(provider, descriptor, instance, password)
            else RemoteGate.read(descriptor, instance).instanceId
        if (checked != null) {
            val policy = finishJoinPolicy(provider, checked, instanceId, password, identity::getOrCreate)
            if (checked.legacy != null) RemoteGate(provider, instanceId).minimumReader()
            policy.requireUnchanged(provider)
        }
        currentCoroutineContext().ensureActive()
        val config = SyncConfiguration(request.endpoint, request.username, request.root, identity.getOrCreate(), instanceId, password != null)
        store.save(config, request.password, password)
        return config
    }

    private suspend fun finishJoinPolicy(
        provider: ObjectProvider, checked: MarkerCheck, instance: String, password: CharArray?, device: () -> String,
    ): EncryptionExpectation {
        if (checked.legacy == null) return checked.expectation
        return encryption.upgrade(provider, checked, checkNotNull(password), device) {
            RemoteGate(provider, instance).minimumReader()
        }
    }

    private suspend fun create(provider: ObjectProvider, format: ByteArray?, instance: ByteArray?, password: CharArray?): String {
        if (format != null || instance != null || provider.get(ENCRYPTION_MARKER_KEY) != null || provider.list("").isNotEmpty()) {
            throw SyncFailure(SyncError.ALREADY_EXISTS)
        }
        val marker = password?.let { encryption.createMarker(identity.getOrCreate(), it) }
        currentCoroutineContext().ensureActive()
        val id = UUID.randomUUID().toString()
        val body = JsonValue.Object.Builder().put("instance_id", id).put("format_version", 4)
            .put("created_at", Instant.now().toString()).build().literal().toByteArray()
        // Both Android create modes must win the same conditional write before
        // an encrypted creator can add a marker. The instance is the last write,
        // so a partially created repository cannot be joined as legacy plaintext.
        val emptyPolicy = EncryptionExpectation(null)
        putVerified(emptyPolicy.guardWrites(provider), FORMAT_KEY, FORMAT.toByteArray())
        if (provider.get(INSTANCE_KEY) != null) throw SyncFailure(SyncError.ALREADY_EXISTS)
        if (marker != null) putVerified(provider, ENCRYPTION_MARKER_KEY, marker)
        val policy = EncryptionExpectation(marker)
        putVerified(policy.guardWrites(provider), INSTANCE_KEY, body)
        RemoteGate(provider, id).minimumReader()
        policy.requireUnchanged(provider)
        return id
    }

    private suspend fun putVerified(provider: ObjectProvider, key: String, bytes: ByteArray) {
        provider.put(key, bytes, ifAbsent = true)
        if (provider.get(key)?.contentEquals(bytes) != true) throw SyncFailure(SyncError.INVALID_REPOSITORY)
    }

    private suspend fun <T> result(operation: String, block: suspend () -> T): SyncResult<T> = mutex.withLock {
        val started = System.nanoTime()
        val correlation = CorrelationId.parse(UUID.randomUUID().toString())
        fun trace(event: String, state: String, status: String = "none") = logger.log(LogRecord(
            LogLevel.INFO, "sync.configuration.$event", correlationId = correlation,
            fields = JsonValue.Object.Builder().put("owner", "sync.configuration").put("phase", operation)
                .put("state", state).put("status", status).put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
                .build(),
        ))
        trace("started", "started")
        val result = try {
            currentCoroutineContext().ensureActive()
            SyncResult.Success(block())
        } catch (cancelled: CancellationException) {
            trace("cancelled", "cancelled", SyncError.CANCELLED.name)
            throw cancelled
        } catch (failure: SyncFailure) {
            SyncResult.Failure(failure.error)
        } catch (_: Exception) {
            SyncResult.Failure(SyncError.STORAGE)
        }
        trace("finished", if (result is SyncResult.Success) "succeeded" else "failed",
            (result as? SyncResult.Failure)?.error?.name ?: "none")
        result
    }

    companion object {
        const val FORMAT_KEY = "data_governance/format.json"
        const val INSTANCE_KEY = "data_governance/instance.json"
        const val ENCRYPTION_MARKER_KEY = ".encryption-marker"
        const val FORMAT = "{\n  \"format_version\": 4,\n  \"min_client\": 3,\n  \"features\": []\n}"
    }
}
