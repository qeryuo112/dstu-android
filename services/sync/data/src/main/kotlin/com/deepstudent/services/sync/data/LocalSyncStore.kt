package com.deepstudent.services.sync.data

import com.deepstudent.platform.security.CredentialStoreError
import com.deepstudent.platform.security.CredentialStoreException
import com.deepstudent.platform.security.CredentialKey
import com.deepstudent.platform.security.CredentialStore
import com.deepstudent.platform.security.MasterKeyFailure
import com.deepstudent.platform.security.SecretBytes
import com.deepstudent.services.sync.api.SyncConfiguration
import com.deepstudent.services.sync.api.PairingMode
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.ConfigurationStore
import com.deepstudent.services.sync.backend.DeviceIdentity
import com.deepstudent.services.sync.backend.SyncFailure
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import java.nio.channels.FileChannel
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class LocalSyncStore(private val directory: File, private val credentials: CredentialStore) : ConfigurationStore, DeviceIdentity {
    private data class Entry(val config: SyncConfiguration, val webdav: CredentialKey, val encryption: CredentialKey?) {
        fun keys(): List<CredentialKey> = listOfNotNull(webdav, encryption)
    }

    private val configFile = File(directory, "configuration.json")
    private val identityFile = File(directory, "device-id")
    private val continuityFile = File(directory, "continuity-binding-v1.json")

    // Test-only cut points exercise retry ordering with real files, not actual power interruption.
    internal var continuityCheckpoint: (String) -> Unit = {}

    override fun getOrCreate(): String = locked { identity() }

    override fun read(): SyncConfiguration? = locked { readEntry()?.config }

    override fun requirePairingTarget(mode: PairingMode, endpoint: String, root: String) = locked {
        val state = readContinuityState() ?: return@locked
        val bound = state as? ContinuityWriterState.Active ?: throw SyncFailure(SyncError.STORAGE)
        if (mode != PairingMode.JOIN || ContinuitySyncBinding.repositoryUrl(endpoint, root) != bound.binding.repositoryUrl) {
            throw SyncFailure(SyncError.INVALID_CONFIGURATION)
        }
    }

    override fun verifyCredentialReferences(configuration: SyncConfiguration) = locked {
        requiredEntry(configuration).keys().forEach { key -> credential { credentials.get(key).close() } }
    }

    override fun save(configuration: SyncConfiguration, password: CharArray, encryptionPassword: CharArray?) = locked {
        if (configuration.deviceId != identity() || configuration.encrypted != (encryptionPassword?.isNotEmpty() == true)) {
            throw SyncFailure(SyncError.INVALID_CONFIGURATION)
        }
        val binding = readContinuityBinding()
        if (binding != null && !binding.matches(configuration)) throw SyncFailure(SyncError.INVALID_CONFIGURATION)
        val previous = readEntry()
        val webdav = writeSecret("webdav", password)
        val encryption = try { encryptionPassword?.takeIf { it.isNotEmpty() }?.let { writeSecret("encryption", it) } }
        catch (failure: Exception) { deleteBestEffort(webdav); throw failure }
        publish(Entry(configuration, webdav, encryption), previous)
    }

    override fun replaceEncryptionPassword(configuration: SyncConfiguration, password: CharArray) = locked {
        val previous = requiredEntry(configuration)
        if (!configuration.encrypted || password.isEmpty()) throw SyncFailure(SyncError.INVALID_CONFIGURATION)
        publish(previous.copy(encryption = writeSecret("encryption", password)), previous)
    }

    private fun writeSecret(kind: String, password: CharArray): CredentialKey {
        val key = CredentialKey.of("sync.$kind.${UUID.randomUUID()}")
        val secret = SecretBytes.fromUtf8(password)
        try { credential { credentials.put(key, secret) } } finally { secret.close() }
        return key
    }

    private fun publish(entry: Entry, previous: Entry?) {
        val config = entry.config
        try {
            val obj = buildJsonObject {
                put("endpoint", config.endpoint)
                put("username", config.username)
                put("root", config.root)
                put("deviceId", config.deviceId)
                put("instanceId", config.instanceId)
                put("credentialRef", entry.webdav.value)
                put("encrypted", config.encrypted)
                entry.encryption?.let { put("encryptionRef", it.value) }
            }
            atomicWrite(configFile, obj.toString())
        } catch (failure: Exception) {
            val published = runCatching { readEntry() == entry }.getOrDefault(true)
            if (!published) (entry.keys() - previous?.keys().orEmpty().toSet()).forEach(::deleteBestEffort)
            throw failure
        }
        (previous?.keys().orEmpty() - entry.keys().toSet()).forEach(::deleteBestEffort)
    }

    private fun deleteBestEffort(key: CredentialKey) { runCatching { credentials.delete(key) } }

    override fun clear(): Unit = locked {
        val keys = readEntry()?.keys().orEmpty()
        if (exists(configFile)) {
            Files.delete(configFile.toPath())
            syncDirectory()
        }
        val outcomes = keys.map { key -> runCatching { credential { credentials.delete(key) } } }
        outcomes.forEach { it.getOrThrow() }
    }

    /**
     * Publish only after trusted composition has activated this exact DataSpace operation and
     * quiesced pre-existing sync sessions. On startup, restore recovery must complete this call
     * before any sync or re-pairing; the local lock cannot revoke already-open remote sessions.
     * This is not proof of writer retirement or a claim. A durable in-progress state blocks all normal access until this operation is retried; the
     * old configuration is fsynced away before the active binding is published atomically.
     */
    fun adoptWriterForContinuity(binding: ContinuitySyncBinding): Unit = locked {
        binding.validate()
        when (val state = readContinuityState()) {
            is ContinuityWriterState.Active -> {
                if (state.binding.operationId == binding.operationId) {
                    if (state.binding != binding) throw SyncFailure(SyncError.INVALID_CONFIGURATION)
                    readEntry() // Same operation must preserve a later valid re-pairing.
                    return@locked
                }
                readEntry()
                writeContinuityState(ContinuityWriterState.Adopting(binding))
            }
            is ContinuityWriterState.Adopting -> {
                if (state.binding != binding) throw SyncFailure(SyncError.STORAGE)
            }
            is ContinuityWriterState.Rotating -> throw SyncFailure(SyncError.STORAGE)
            null -> {
                readEntry()
                writeContinuityState(ContinuityWriterState.Adopting(binding))
            }
        }
        continuityCheckpoint("adoption-pending")
        val old = readEntry(checkIdentity = false)
        removeConfiguration()
        continuityCheckpoint("configuration-removed")
        writeContinuityState(ContinuityWriterState.Active(binding))
        continuityCheckpoint("continuity-published")
        old?.keys()?.forEach(::deleteBestEffort)
        Unit
    }

    /** Called only after ordinary restore activation. A new UUID breaks the continuity binding. */
    fun rotateWriterForRestore(operationId: String): Boolean = locked {
        requireRotationUuid(operationId)
        if (readContinuityState() == null && exists(identityFile) && identity() == operationId) {
            readEntry()
            return@locked true
        }
        if (!beginWriterRotation(operationId)) return@locked false
        continuityCheckpoint("rotation-pending")
        val old = readEntry(checkIdentity = false)
        removeConfiguration()
        continuityCheckpoint("rotation-configuration-removed")
        atomicWrite(identityFile, operationId)
        continuityCheckpoint("rotation-identity-published")
        Files.delete(continuityFile.toPath())
        syncDirectory()
        old?.keys()?.forEach(::deleteBestEffort)
        true
    }

    private fun beginWriterRotation(operationId: String): Boolean {
        when (val state = readContinuityState()) {
            is ContinuityWriterState.Rotating -> {
                if (state.operationId != operationId) throw SyncFailure(SyncError.STORAGE)
            }
            is ContinuityWriterState.Adopting -> throw SyncFailure(SyncError.STORAGE)
            else -> {
                if (state == null && !exists(identityFile)) {
                    if (readEntry() != null) throw SyncFailure(SyncError.STORAGE)
                    return false
                }
                readEntry()
                writeContinuityState(ContinuityWriterState.Rotating(operationId))
            }
        }
        return true
    }

    private fun removeConfiguration() {
        rejectLinks(configFile)
        if (exists(configFile)) {
            Files.delete(configFile.toPath())
            syncDirectory()
        }
    }

    fun password(configuration: SyncConfiguration? = null): SecretBytes = locked {
        val key = requiredEntry(configuration).webdav
        credential { credentials.get(key) }
    }

    fun encryptionPassword(configuration: SyncConfiguration): SecretBytes = locked {
        val key = requiredEntry(configuration).encryption ?: throw SyncFailure(SyncError.INVALID_CONFIGURATION)
        credential { credentials.get(key) }
    }

    private fun requiredEntry(expected: SyncConfiguration?): Entry =
        readEntry()?.takeIf { expected == null || it.config == expected } ?: throw SyncFailure(SyncError.INVALID_CONFIGURATION)

    private fun identity(): String {
        readContinuityBinding()?.let { return it.writerId }
        rejectLinks(identityFile)
        if (exists(identityFile)) {
            val value = readBounded(identityFile)
            val uuid = runCatching { UUID.fromString(value) }.getOrNull()
            if (uuid == null || uuid.toString() != value || uuid.version() != 4) throw SyncFailure(SyncError.STORAGE)
            return value
        }
        rejectLinks(configFile)
        if (exists(configFile)) throw SyncFailure(SyncError.STORAGE)
        return UUID.randomUUID().toString().also { atomicWrite(identityFile, it) }
    }

    private fun readEntry(checkIdentity: Boolean = true): Entry? {
        if (checkIdentity) readContinuityBinding()
        rejectLinks(configFile)
        if (!exists(configFile)) return null
        try {
            return decodeEntry(Json.parseToJsonElement(readBounded(configFile)).jsonObject, checkIdentity)
        } catch (failure: SyncFailure) { throw failure } catch (_: Exception) { throw SyncFailure(SyncError.STORAGE) }
    }

    private fun decodeEntry(obj: JsonObject, checkIdentity: Boolean): Entry {
        fun field(name: String) = obj.getValue(name).jsonPrimitive.content
        val encrypted = obj["encrypted"]?.let { requireNotNull(it.jsonPrimitive.booleanOrNull) } ?: false
        val encryption = obj["encryptionRef"]?.let { CredentialKey.of(it.jsonPrimitive.content) }
        require(encrypted == (encryption != null) && encryption?.value?.startsWith("sync.encryption.") != false)
        val config = SyncConfiguration(field("endpoint"), field("username"), field("root"), field("deviceId"), field("instanceId"), encrypted)
        if (checkIdentity) validateEntryIdentity(config)
        val webdav = CredentialKey.of(field("credentialRef"))
        require(webdav.value.startsWith("sync.webdav."))
        return Entry(config, webdav, encryption)
    }

    private fun validateEntryIdentity(config: SyncConfiguration) {
        if (config.deviceId != identity()) throw SyncFailure(SyncError.STORAGE)
        val binding = readContinuityBinding()
        if (binding != null && !runCatching { binding.matches(config) }.getOrDefault(false)) throw SyncFailure(SyncError.STORAGE)
    }

    private fun readContinuityBinding(): ContinuitySyncBinding? = when (val state = readContinuityState()) {
        null -> null
        is ContinuityWriterState.Active -> state.binding
        else -> throw SyncFailure(SyncError.STORAGE)
    }

    private fun readContinuityState(): ContinuityWriterState? {
        rejectLinks(continuityFile)
        if (!exists(continuityFile)) return null
        return ContinuitySyncBindingState.decode(readBounded(continuityFile))
    }

    private fun writeContinuityState(state: ContinuityWriterState) =
        atomicWrite(continuityFile, ContinuitySyncBindingState.encode(state))

    private fun exists(file: File): Boolean = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        true
    } catch (_: NoSuchFileException) {
        false
    } catch (_: Exception) {
        throw SyncFailure(SyncError.STORAGE)
    }

    private fun readBounded(file: File): String {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) throw SyncFailure(SyncError.STORAGE)
        val output = ByteArrayOutputStream()
        FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(4096)
            while (true) {
                val count = channel.read(buffer)
                if (count < 0) break
                if (output.size() + count > 65536) throw SyncFailure(SyncError.LIMIT_EXCEEDED)
                output.write(buffer.array(), 0, count)
                buffer.clear()
            }
        }
        return output.toByteArray().decodeToString(throwOnInvalidSequence = true)
    }

    private fun <T> locked(block: () -> T): T = synchronized(processLock) {
        rejectLinks(directory)
        if (!exists(directory) && !directory.mkdirs()) throw SyncFailure(SyncError.STORAGE)
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) throw SyncFailure(SyncError.STORAGE)
        val lock = File(directory, "store.lock")
        rejectLinks(lock)
        RandomAccessFile(lock, "rw").channel.use { channel -> channel.lock().use { block() } }
    }

    private fun rejectLinks(file: File) {
        var path = file.toPath().toAbsolutePath()
        while (true) {
            if (Files.isSymbolicLink(path)) throw SyncFailure(SyncError.STORAGE)
            path = path.parent ?: break
        }
    }

    private fun atomicWrite(file: File, value: String) {
        if (value.toByteArray().size > 65536) throw SyncFailure(SyncError.LIMIT_EXCEEDED)
        rejectLinks(file)
        val temp = File.createTempFile("sync-", ".tmp", directory)
        try {
            java.io.FileOutputStream(temp).use { it.write(value.toByteArray()); it.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory()
        } finally { temp.delete() }
    }

    private fun syncDirectory() {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    private inline fun <T> credential(block: () -> T): T = try {
        block()
    } catch (error: CredentialStoreException) {
        val code = when {
            error.masterKeyFailure == MasterKeyFailure.PERMANENTLY_LOST -> SyncError.CREDENTIAL_KEY_LOST
            error.error == CredentialStoreError.INTEGRITY_FAILURE -> SyncError.CREDENTIAL_INTEGRITY
            else -> SyncError.CREDENTIAL_UNAVAILABLE
        }
        throw SyncFailure(code)
    } catch (_: Exception) {
        throw SyncFailure(SyncError.CREDENTIAL_UNAVAILABLE)
    }

    companion object { private val processLock = Any() }
}
