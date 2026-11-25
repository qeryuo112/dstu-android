package com.deepstudent.app.backup

import com.deepstudent.app.security.MainSecurityResources
import com.deepstudent.app.storage.MainStorageResources
import com.deepstudent.app.sync.MainSyncFactory
import com.deepstudent.core.common.id.Digests
import com.deepstudent.engines.zstd.ZstdEngine
import com.deepstudent.platform.database.BundledMigrationSource
import com.deepstudent.platform.security.AndroidKeystoreKeyWrapper
import com.deepstudent.platform.security.SecurityFactory
import com.deepstudent.platform.security.SecurityPorts
import com.deepstudent.services.backup.data.BackupIdentityStore
import com.deepstudent.services.sync.api.DomainSyncAdapter
import com.deepstudent.services.sync.api.PairingMode
import com.deepstudent.services.sync.api.PairingRequest
import com.deepstudent.services.sync.api.SyncResult
import com.deepstudent.services.sync.backend.PairingService
import com.deepstudent.services.sync.backend.RepositoryEncryption
import com.deepstudent.services.sync.backend.SyncWireCodec
import com.deepstudent.services.sync.backend.transfer.ChangeTransfer
import com.deepstudent.services.sync.backend.transfer.SyncCompression
import com.deepstudent.services.sync.backend.transfer.SyncUploadState
import com.deepstudent.services.sync.data.LocalSyncStore
import com.deepstudent.services.sync.data.transfer.DsbkSyncCryptography
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import java.io.IOException
import java.security.KeyPair
import java.security.Signature
import java.time.Instant
import java.util.UUID

/** True App composition over an isolated legacy snapshot, trusted signer and governed TLS WebDAV fixture. */
internal class ManagedContinuityScenario(private val repo: ManagedContinuityTestRepository, private val administrator: KeyPair) :
    AutoCloseable {
    val fixture = LegacyMobileFixtureSupport()
    val target = fixture.target
    private val syncDirectory = target.noBackupFilesDir.canonicalFile.resolve("sync")
    private val alias = "fixture.continuity.${UUID.randomUUID()}"
    private val source = fixture.original("continuity")
    val sourceUri get() = fixture.uri(source)
    var now: Instant = Instant.now()
    var breakPublication = false
    var expireAfterClaimCheckpoint = false
    var failAfterClaimCheckpoint = false
    private var opens = 0
    private val managed = ManagedContinuityRestore(target, {
        if (breakPublication && ++opens == 2) throw IOException("injected identity publication failure")
        openSecurity()
    }, repo.providerFactory, ::clock)
    var oldWriter: String? = null
        private set

    private fun clock(): Instant {
        val claimed = target.noBackupFilesDir.resolve("backup-continuity/continuity-claim").isFile
        if (claimed && failAfterClaimCheckpoint) error("injected deterministic candidate validation failure")
        return if (claimed && expireAfterClaimCheckpoint) now.plusSeconds(3_600) else now
    }

    fun coordinator() = BackupRestoreCoordinator(
        target,
        OrdinaryRestoreIdentity(target, ::openSecurity),
        ContinuityQueuedRestore(target, managed),
    )

    suspend fun seedTargetBeforeRestore() {
        fixture.seedLocalState()
        oldWriter = pair()
        assertNotEquals(ManagedContinuityTestRepository.WRITER, oldWriter)
    }

    fun grant(request: ContinuityTarget, sequence: Long = 5, issued: Instant = now.minusSeconds(30)): ContinuityFiles {
        val sidecar = fixture.copySidecarAndCheckFrozenDigest()
        val document = JSONObject(sidecar.readText())
        document.getJSONObject("state").useRepository(repo.url)
        document.getJSONObject("expected").getJSONObject("state").useRepository(repo.url)
        val side = document.toString().toByteArray()
        val installation = document.getJSONObject("source_installation")
        val fields = listOf(
            "dstu-continuity-grant-v1", repo.url, ManagedContinuityTestRepository.INSTANCE, "webdav", repo.url,
            installation.getString("applicationId"), installation.getString("versionName"), installation.getLong("versionCode").toString(),
            installation.getString("installationId"), ManagedContinuityTestRepository.WRITER, sequence.toString(),
            Digests.sha256Hex(source.readBytes()), Digests.sha256Hex(side), request.installationId, request.operationId,
            issued.epochSecond.toString(), issued.plusSeconds(600).epochSecond.toString(), "writer-permanently-revoked",
            "snapshot-sequence-verified", "claim-store-atomic-immutable", "2", ManagedContinuityTestRepository.WRITER,
            ManagedContinuityTestRepository.PEER,
        )
        val signed = fields.joinToString("\n", postfix = "\n").toByteArray()
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(administrator.private)
            update(signed)
            sign()
        }
        val spki = administrator.public.encoded
        return ContinuityFiles(side, signed, signature, spki, Digests.sha256Hex(spki))
    }

    suspend fun reviewAndQueue(coordinator: BackupRestoreCoordinator): ContinuityTarget {
        val actions = SafContinuityEntryActions(target, coordinator)
        val target = actions.issueTarget()
        val material = grant(target)
        val documents = savedDocuments(material)
        val review = actions.review(documents, material.fingerprint)
        assertEquals(5L, review.lastSequence)
        assertTrue(actions.schedule(documents, material.fingerprint, review))
        assertEquals(BackupRestoreUiStatus.QUEUED, coordinator.status.value)
        return target
    }

    private fun savedDocuments(files: ContinuityFiles): Map<ContinuityDocument, android.net.Uri> {
        val data = mapOf(
            ContinuityDocument.SIDECAR to files.sidecar,
            ContinuityDocument.GRANT to files.grant,
            ContinuityDocument.SIGNATURE to files.signature,
            ContinuityDocument.KEY to files.keyDer,
        )
        return data.mapValues { (kind, bytes) ->
            fixture.uri(fixture.scratch.resolve("managed-${kind.name}").apply { writeBytes(bytes) })
        } + (ContinuityDocument.ARCHIVE to sourceUri)
    }

    fun writer() = openSecurity().use { local(it).getOrCreate() }

    suspend fun checkRestoredContent() {
        MainStorageResources.open(target).use { storage ->
            val seeds = fixture.seeds
            val id = seeds.getJSONArray("noteIds").getString(0)
            assertEquals(seeds.getJSONArray("noteContents").getString(0), storage.notes.get(id)?.content)
            val blob = storage.activeRoot.resolve("vfs_blobs/${seeds.getString("blobRelativePath")}")
            assertEquals(seeds.getString("fileSha256"), Digests.sha256Hex(blob.readBytes()))
            val state = BackupIdentityStore(storage.databaseForSync())
                .readSnapshot(ManagedContinuityTestRepository.INSTANCE, ManagedContinuityTestRepository.WRITER)
            assertEquals(5L, state.publishedSequence)
            assertEquals(ManagedContinuityTestRepository.INSTANCE, state.binding?.instanceId)
            assertEquals(12L, state.sourceCursors.single().lastSeq)
            assertEquals(listOf(3L, 7L), state.watermarks.map { it.lastAppliedOffset }.sorted())
            assertEquals(1, state.receipts.size)
        }
    }

    suspend fun assertUnchanged() {
        assertEquals(oldWriter, writer())
        MainStorageResources.open(target).use { storage ->
            assertEquals(target.filesDir.resolve("active").canonicalFile, storage.activeRoot.canonicalFile)
        }
    }

    /** Snapshot only fixture-owned recovery files; intent alone is not a confirmed claim. */
    fun pendingEvidence(operationId: String, confirmedClaim: Boolean): Map<String, String> {
        val root = target.noBackupFilesDir
        val request = PendingBackupRestore(root.resolve("backup-restore")).request()
        assertEquals(operationId, request?.id)
        assertTrue(request?.continuity == true)
        val paths = mutableListOf(
            "backup-restore/pending", "backup-restore/confirmed.zip", "backup-continuity/continuity-target",
            "backup-continuity/continuity-sidecar", "backup-continuity/continuity-grant", "backup-continuity/continuity-signature",
            "backup-continuity/continuity-key", "backup-continuity/continuity-receipt", "backup-continuity/continuity-claim-intent",
        )
        val claim = root.resolve("backup-continuity/continuity-claim")
        assertEquals(confirmedClaim, claim.isFile)
        if (confirmedClaim) paths += "backup-continuity/continuity-claim"
        assertFalse(root.resolve("backup-continuity/continuity-complete").exists())
        return paths.associateWith { path ->
            val file = root.resolve(path)
            assertTrue("missing pending evidence: $path", file.isFile)
            Digests.sha256Hex(file.readBytes())
        }
    }

    suspend fun publishNextThroughRealPipeline() = coroutineScope {
        assertEquals(ManagedContinuityTestRepository.WRITER, pair())
        openSecurity().use { ports ->
            val local = local(ports)
            val migrations = BundledMigrationSource(target)
            val encryption = RepositoryEncryption(DsbkSyncCryptography(ports.dsbk))
            MainStorageResources.open(target).use { storage ->
                storage.notes.create(
                    com.deepstudent.features.notes.api.CreateNote("after migration", "published through the new installation"),
                )
                val transfer = MainSyncFactory(encryption, migrations).open(
                    storage.databaseForSync(),
                    storage.activeRoot.resolve("vfs_blobs"),
                    local,
                    repo.providerFactory,
                    this,
                    syncDirectory,
                    incomingDecksDatabase = storage::incomingDecksDatabaseForSync,
                )
                assertEquals(SyncResult.Success(Unit), transfer.synchronize())
            }
        }
        assertTrue(repo.hasPublishedSequence(6))
    }

    /** A second in-process protocol reader starts at cursor 4; this is not a second physical device. */
    suspend fun checkIndependentReaderConsumedFiveAndSix() {
        val provider = repo.providerFactory.open(
            repo.url,
            ManagedContinuityTestRepository.USER,
            "",
            ManagedContinuityTestRepository.NEW_PASSWORD.toCharArray(),
        )
        val seen = mutableListOf<Pair<Long, Int>>()
        var cursor = 4L
        val writerHash = SyncWireCodec.deviceHash(ManagedContinuityTestRepository.WRITER)
        val adapter = object : DomainSyncAdapter {
            override fun pending() = emptyList<ByteArray>()
            override fun markPublished(changeLogIds: List<Long>, version: Long) = Unit
            override fun cursor(device: String): Long {
                assertEquals(writerHash, device)
                return cursor
            }
            override fun apply(device: String, seq: Long, changes: List<ByteArray>) {
                assertEquals(writerHash, device)
                cursor = seq
                seen += seq to changes.size
            }
        }
        val state = object : SyncUploadState {
            override fun published() = 0L
            override fun markPublished(seq: Long) = Unit
        }
        val zstd = ZstdEngine()
        val compression = object : SyncCompression {
            override fun encode(bytes: ByteArray) = zstd.encode(bytes)
            override fun decode(bytes: ByteArray) = zstd.decode(bytes)
        }
        ChangeTransfer(provider, compression, state, adapter, "independent-fixture-reader").download()
        assertEquals(listOf(5L, 6L), seen.map { it.first })
        assertTrue("the successor publication must contain changes", seen.last().second > 0)
    }

    private suspend fun pair(): String = openSecurity().use { ports ->
        val store = local(ports)
        val service = PairingService(store, store, repo.providerFactory)
        val result = service.pair(
            PairingMode.JOIN,
            PairingRequest(
                repo.url,
                ManagedContinuityTestRepository.USER,
                "",
                ManagedContinuityTestRepository.NEW_PASSWORD.toCharArray(),
            ),
        )
        assertTrue(result.toString(), result is SyncResult.Success)
        (result as SyncResult.Success).value.deviceId
    }

    private fun openSecurity(): SecurityPorts = MainSecurityResources.open(target) { _, audit ->
        SecurityFactory.create(fixture.scratch.canonicalFile.resolve("continuity-security"), alias, audit = audit)
    }

    private fun local(ports: SecurityPorts) = LocalSyncStore(syncDirectory, ports.credentials)

    override fun close() {
        try {
            AndroidKeystoreKeyWrapper(alias).deleteKey()
        } finally {
            fixture.close()
        }
    }

    private fun JSONObject.useRepository(url: String) {
        getJSONArray("instanceBinding").getJSONObject(0).put("provider", "webdav").put("endpoint_hint", url)
    }
}
