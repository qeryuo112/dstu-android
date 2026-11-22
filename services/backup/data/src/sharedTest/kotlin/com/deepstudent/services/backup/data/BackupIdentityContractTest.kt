package com.deepstudent.services.backup.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.backup.backend.*
import com.deepstudent.services.sync.backend.SyncWireCodec
import com.deepstudent.services.sync.backend.transfer.PendingPublication
import com.deepstudent.services.sync.data.transfer.SqlSyncManifest
import com.deepstudent.services.sync.data.transfer.SqlUploadState
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Same tests run with the actual platform database driver on JVM and Android. */
abstract class BackupIdentityContractTest {
    protected abstract val root: File
    protected abstract val migrations: MigrationSource
    protected abstract fun fixture(name: String): ByteArray
    @After fun cleanup() { root.deleteRecursively() }

    private fun open(): SqlDatabase = DatabaseFactory.openShared(File(root, "candidate-vfs.db"), SharedDatabase.VFS, migrations)
    private fun matched(transform: (ByteArray) -> ByteArray = { it }): ContinuityValidation.Matched {
        val base = "legacy-mobile-continuity-continuity-v1"
        val zip = fixture("$base.zip")
        val frozenSidecar = fixture("$base.continuity.json")
        assertEquals("774b1aea192eaec9f991c127aa275689b624b6679724e6ac6dea1316f5a13924", hash(zip))
        assertEquals("9c6b0fa1c3b93b8677514e6add0f0aecec4521e4ee4289cbe2d1f38a09bcadb6", hash(frozenSidecar))
        val sidecar = transform(frozenSidecar)
        val manifestBytes = ZipInputStream(ByteArrayInputStream(zip)).use { stream ->
            var entry = stream.nextEntry
            while (entry != null && entry.name != "manifest.json") entry = stream.nextEntry
            check(entry != null)
            stream.readBytes()
        }
        val manifest = ManifestCodec.decode(manifestBytes.toString(Charsets.UTF_8))
        val trust = ContinuityTrust(
            ContinuityArchiveEvidence(hash(zip), zip.size.toLong(), manifest),
            ContinuitySourceInstallation("com.deepstudent.app", "0.20.0", 1114586, "installation-legacy-mobile-demo"),
            ContinuityRepository("default", "local", "file:///deep-student/local/sync"),
            ContinuityWriterConfirmation("device-7f3a91c2", stopped = true, unchangedSinceSnapshot = true), hash(sidecar),
        )
        return ContinuityValidator.validate(sidecar, trust) as ContinuityValidation.Matched
    }

    /** Ledger is a no-op only for transaction-unit fakes; production must consume PENDING. */
    private abstract class BackupIdentityTestClaim : BackupIdentityClaimPort() {
        override fun completePendingInTransaction(binding: BackupIdentityClaimBinding, candidate: SqlOperations) = Unit
    }

    /** Test-only stand-in; production must independently establish and keep an actual global fence. */
    private val fakeClaim = object : BackupIdentityTestClaim() {
        override fun withVerifiedExclusiveClaim(binding: BackupIdentityClaimBinding, commit: (BackupIdentityPeerAliases) -> Unit) {
            assertEquals("default", binding.instanceId)
            assertEquals("device-7f3a91c2", binding.writerId)
            assertEquals("774b1aea192eaec9f991c127aa275689b624b6679724e6ac6dea1316f5a13924", binding.archiveSha256)
            assertEquals("9c6b0fa1c3b93b8677514e6add0f0aecec4521e4ee4289cbe2d1f38a09bcadb6", binding.sidecarSha256)
            assertEquals("installation-legacy-mobile-demo", binding.sourceInstallationId)
            commit(BackupIdentityPeerAliases(binding.uploaderIds.associateWith { it }))
        }
    }

    @Test fun continuityPersistsAndExistingUploadAndManifestReadersSeeSameRows() {
        val matched = matched()
        open().use { db ->
            val store = BackupIdentityStore(db)
            store.restoreContinuity(matched, fakeClaim)
            val snapshot = store.readSnapshot("default", "device-7f3a91c2")
            assertEquals(matched.state.repository, snapshot.binding)
            assertEquals(5L, snapshot.publishedSequence)
            assertEquals(matched.state.cursors, snapshot.sourceCursors)
            assertEquals(setOf("device-91bc4de0", SyncWireCodec.deviceHash("device-91bc4de0")), snapshot.cursors.map { it.uploaderDeviceId }.toSet())
            assertTrue(snapshot.cursors.all { it.lastSeq == 12L })
            assertEquals(matched.state.watermarks.toSet(), snapshot.watermarks.toSet())
            assertEquals(matched.state.publications, snapshot.publications)
            assertEquals((matched.state.processedKeys + matched.takeoverKey).toSet(), snapshot.processedKeys.toSet())
            assertEquals(matched.takeoverKey, snapshot.receipts.single().takeoverKey)
            assertEquals(matched.sidecarSha256, snapshot.receipts.single().sidecarSha256)
            val journal = SqlUploadState(db, "default", "device-7f3a91c2")
            assertEquals(5L, journal.published())
            val manifest = SqlSyncManifest(db, "default", "device-7f3a91c2", journal, emptyMap()).snapshot()
            val cursors = manifest["cursors"] as JsonValue.Object
            assertEquals(JsonValue.integer(12), cursors["device-91bc4de0"])
            assertEquals(JsonValue.integer(12), cursors[SyncWireCodec.deviceHash("device-91bc4de0")])
            assertEquals(JsonValue.integer(5), manifest["published_max_seq"])
            assertFalse(snapshot.toString().contains("default"))
            assertFalse(snapshot.receipts.single().toString().contains(matched.takeoverKey))
        }
        open().use { db ->
            val saved = BackupIdentityStore(db).readSnapshot("default", "device-7f3a91c2")
            assertEquals(5L, saved.publishedSequence)
            assertEquals(matched.takeoverKey, saved.receipts.single().takeoverKey)
        }
    }

    @Test fun ordinaryResetKeepsUploadPublicationPendingAndIndependentReceipt() {
        val matched = matched()
        open().use { db ->
            val store = BackupIdentityStore(db)
            store.restoreContinuity(matched, fakeClaim)
            val journal = SqlUploadState(db, "default", "device-7f3a91c2")
            journal.reserve(PendingPublication("future-object", 6, 1, byteArrayOf(1, 2), listOf(5)))
            db.execute("CREATE TABLE __android_sync_versions_v2(instance TEXT,record_id TEXT,deleted INTEGER)")
            db.execute("INSERT INTO __android_sync_versions_v2 VALUES('default','note',1)")
            assertSame(BackupIdentityRePairRequired, store.resetForOrdinaryRestore())
            val state = store.readSnapshot("default", "device-7f3a91c2")
            assertNull(state.binding)
            assertTrue(state.cursors.isEmpty()); assertTrue(state.sourceCursors.isEmpty()); assertTrue(state.watermarks.isEmpty()); assertTrue(state.processedKeys.isEmpty())
            assertEquals(5L, state.publishedSequence)
            assertEquals(matched.state.publications, state.publications)
            assertEquals(matched.takeoverKey, state.receipts.single().takeoverKey)
            assertEquals("future-object", journal.pending()?.key)
            assertEquals(1L, db.query("SELECT deleted FROM __android_sync_versions_v2") { it.long(0) }.single())
            val failure = assertThrows(BackupIdentityException::class.java) { store.restoreContinuity(matched, fakeClaim) }
            assertEquals(BackupIdentityFailure.DUPLICATE_TAKEOVER, failure.diagnostic)
            assertNull(store.readSnapshot("default", "device-7f3a91c2").binding)
        }
    }

    @Test fun absentOrMalformedClaimCannotWriteOrInvokeCommitLate() {
        val matched = matched()
        open().use { db ->
            val store = BackupIdentityStore(db)
            val absent = assertThrows(BackupIdentityException::class.java) { store.restoreContinuity(matched) }
            assertEquals(BackupIdentityFailure.CLAIM_UNAVAILABLE, absent.diagnostic)
            var late: ((BackupIdentityPeerAliases) -> Unit)? = null
            val bad = object : BackupIdentityTestClaim() {
                override fun withVerifiedExclusiveClaim(binding: BackupIdentityClaimBinding, commit: (BackupIdentityPeerAliases) -> Unit) { late = commit }
            }
            val failure = assertThrows(BackupIdentityException::class.java) { store.restoreContinuity(matched, bad) }
            assertEquals(BackupIdentityFailure.CLAIM_PROTOCOL, failure.diagnostic)
            assertEquals(BackupIdentityFailure.CLAIM_PROTOCOL, assertThrows(BackupIdentityException::class.java) { late!!(BackupIdentityPeerAliases(emptyMap())) }.diagnostic)
            val noLedger = object : BackupIdentityClaimPort() {
                override fun withVerifiedExclusiveClaim(binding: BackupIdentityClaimBinding, commit: (BackupIdentityPeerAliases) -> Unit) {
                    commit(BackupIdentityPeerAliases(binding.uploaderIds.associateWith { it }))
                }
            }
            assertEquals(BackupIdentityFailure.CLAIM_UNAVAILABLE,
                assertThrows(BackupIdentityException::class.java) { store.restoreContinuity(matched, noLedger) }.diagnostic)
            assertTrue(db.query("SELECT 1 FROM sqlite_master WHERE name='__dstu_identity_receipt'") { it.long(0) }.isEmpty())
        }
    }

    @Test fun unsafeOldWriterPendingPublicationFailsWithoutChangingCandidate() {
        val matched = matched()
        open().use { db ->
            val journal = SqlUploadState(db, "default", "device-7f3a91c2")
            journal.reserve(PendingPublication("old-pending", 6, 1, byteArrayOf(9), listOf(2)))
            val failure = assertThrows(BackupIdentityException::class.java) { BackupIdentityStore(db).restoreContinuity(matched, fakeClaim) }
            assertEquals(BackupIdentityFailure.PENDING_PUBLICATION, failure.diagnostic)
            assertEquals("old-pending", journal.pending()?.key)
            assertTrue(db.query("SELECT 1 FROM sqlite_master WHERE name='__dstu_identity_receipt'") { it.long(0) }.isEmpty())
        }
    }

    @Test fun injectedFailureAfterReceiptRollsBackEveryMutation() {
        val matched = matched()
        open().use { db ->
            val store = BackupIdentityStore(db)
            store.resetForOrdinaryRestore()
            db.execute("INSERT INTO __android_sync_cursor_v2 VALUES('default','prior',2)")
            db.execute("INSERT INTO sync_upload_state VALUES('default','device-7f3a91c2',3)")
            val throwing = object : SqlDatabase by db {
                override fun <T> transaction(block: SqlOperations.() -> T): T = db.transaction {
                    val actual = this
                    val wrapper = object : SqlOperations by actual {
                        override fun execute(sql: String, args: List<SqlValue>) {
                            actual.execute(sql, args)
                            if (sql.startsWith("INSERT INTO __dstu_identity_receipt")) error("injected failure")
                        }
                    }
                    wrapper.block()
                }
            }
            assertThrows(IllegalStateException::class.java) { BackupIdentityStore(throwing).restoreContinuity(matched, fakeClaim) }
            val after = store.readSnapshot("default", "device-7f3a91c2")
            assertNull(after.binding); assertEquals(3L, after.publishedSequence)
            assertEquals(listOf(ContinuityCursor("prior", 2)), after.cursors)
            assertTrue(after.watermarks.isEmpty()); assertTrue(after.publications.isEmpty())
            assertTrue(after.processedKeys.isEmpty()); assertTrue(after.receipts.isEmpty())
        }
    }

    @Test fun conflictAndWrongDatabaseFailClosed() {
        val matched = matched()
        open().use { db ->
            val store = BackupIdentityStore(db)
            store.resetForOrdinaryRestore()
            db.execute("INSERT INTO __dstu_identity_binding VALUES('default','other','endpoint')")
            val failure = assertThrows(BackupIdentityException::class.java) { store.restoreContinuity(matched, fakeClaim) }
            assertEquals(BackupIdentityFailure.BINDING_CONFLICT, failure.diagnostic)
            assertEquals("other", store.readSnapshot("default", "device-7f3a91c2").binding?.provider)
            assertTrue(store.readSnapshot("default", "device-7f3a91c2").receipts.isEmpty())
        }
        DatabaseFactory.openLocal(File(root, "not-vfs.db")).use { db ->
            val failure = assertThrows(BackupIdentityException::class.java) { BackupIdentityStore(db).resetForOrdinaryRestore() }
            assertEquals(BackupIdentityFailure.NOT_VFS, failure.diagnostic)
            assertTrue(db.query("SELECT 1 FROM sqlite_master WHERE name='__dstu_identity_meta'") { it.long(0) }.isEmpty())
        }
    }

    @Test fun maximumWatermarksAndSafeUploadSurviveWhilePublicationCollisionRollsBack() {
        val matched = matched()
        open().use { db ->
            val store = BackupIdentityStore(db)
            store.resetForOrdinaryRestore()
            db.execute("INSERT INTO sync_upload_state VALUES('default','device-7f3a91c2',3)")
            db.execute("INSERT INTO __dstu_identity_watermark VALUES('default','device-91bc4de0','blob',8)")
            db.execute("INSERT INTO __dstu_identity_publication VALUES('default','device-7f3a91c2','blob','different-op',4)")
            assertEquals(BackupIdentityFailure.PUBLICATION_CONFLICT,
                assertThrows(BackupIdentityException::class.java) { store.restoreContinuity(matched, fakeClaim) }.diagnostic)
            assertTrue(store.readSnapshot("default", "device-7f3a91c2").receipts.isEmpty())
            db.execute("DELETE FROM __dstu_identity_publication")
            store.restoreContinuity(matched, fakeClaim)
            val state = store.readSnapshot("default", "device-7f3a91c2")
            assertEquals(5L, state.publishedSequence)
            assertEquals(8L, state.watermarks.single { it.kind == "blob" }.lastAppliedOffset)
        }
    }

    @Test fun incompatibleContractAndTriggersAreRejected() {
        val matched = matched()
        open().use { db ->
            val store = BackupIdentityStore(db)
            store.resetForOrdinaryRestore()
            db.execute("UPDATE __dstu_identity_meta SET version=2")
            assertEquals(BackupIdentityFailure.CONTRACT_MISMATCH,
                assertThrows(BackupIdentityException::class.java) { store.restoreContinuity(matched, fakeClaim) }.diagnostic)
            db.execute("UPDATE __dstu_identity_meta SET version=1")
            db.execute("CREATE TRIGGER bad_identity AFTER INSERT ON __dstu_identity_binding BEGIN DELETE FROM sync_upload_state; END")
            assertEquals(BackupIdentityFailure.CONTRACT_MISMATCH,
                assertThrows(BackupIdentityException::class.java) { store.restoreContinuity(matched, fakeClaim) }.diagnostic)
            assertEquals(0L, db.query("SELECT count(*) FROM __dstu_identity_receipt") { it.long(0) }.single())
        }
    }

    @Test fun traceIsCorrelatedAndDoesNotLeakIdentityOrFailureMessages() {
        val matched = matched()
        open().use { db ->
            val events = mutableListOf<BackupIdentityLogEvent>()
            val store = BackupIdentityStore(db, BackupIdentityLog(events::add))
            store.restoreContinuity(matched, fakeClaim)
            assertEquals(listOf(BackupIdentityStatus.STARTED, BackupIdentityStatus.COMMITTED), events.map { it.status })
            assertEquals(1, events.map { it.correlationId }.distinct().size)
            assertTrue(events.all { it.owner == "services/backup/data" && it.phase == "identity-restore" && it.elapsedMillis >= 0 })
            assertFalse(events.toString().contains("device-")); assertFalse(events.toString().contains("default"))
            events.clear()
            assertThrows(BackupIdentityException::class.java) { store.restoreContinuity(matched, fakeClaim) }
            assertEquals(BackupIdentityFailure.DUPLICATE_TAKEOVER, events.last().diagnostic)
        }
    }

    @Test fun aPortFailureAfterCommitIsExplicitlyUnknownAndReconciledByReceipt() {
        val matched = matched()
        open().use { db ->
            val store = BackupIdentityStore(db)
            val broken = object : BackupIdentityTestClaim() {
                override fun withVerifiedExclusiveClaim(binding: BackupIdentityClaimBinding, commit: (BackupIdentityPeerAliases) -> Unit) {
                    commit(BackupIdentityPeerAliases(binding.uploaderIds.associateWith { it }))
                    error("remote completion disconnected")
                }
            }
            val failure = assertThrows(BackupIdentityException::class.java) { store.restoreContinuity(matched, broken) }
            assertEquals(BackupIdentityFailure.CLAIM_COMPLETION_UNKNOWN, failure.diagnostic)
            assertEquals(matched.takeoverKey, store.readSnapshot("default", "device-7f3a91c2").receipts.single().takeoverKey)
            assertEquals(BackupIdentityFailure.DUPLICATE_TAKEOVER,
                assertThrows(BackupIdentityException::class.java) { store.restoreContinuity(matched, fakeClaim) }.diagnostic)
        }
    }

    @Test fun aClaimCannotMoveTheCommitToAnotherThread() {
        val matched = matched()
        open().use { db ->
            var backgroundFailure: Throwable? = null
            val wrongThread = object : BackupIdentityTestClaim() {
                override fun withVerifiedExclusiveClaim(binding: BackupIdentityClaimBinding, commit: (BackupIdentityPeerAliases) -> Unit) {
                    Thread { try { commit(BackupIdentityPeerAliases(binding.uploaderIds.associateWith { it })) } catch (failure: Throwable) { backgroundFailure = failure } }.apply { start(); join() }
                }
            }
            val failure = assertThrows(BackupIdentityException::class.java) { BackupIdentityStore(db).restoreContinuity(matched, wrongThread) }
            assertEquals(BackupIdentityFailure.CLAIM_PROTOCOL, failure.diagnostic)
            assertEquals(BackupIdentityFailure.CLAIM_PROTOCOL, (backgroundFailure as BackupIdentityException).diagnostic)
            assertTrue(db.query("SELECT 1 FROM sqlite_master WHERE name='__dstu_identity_receipt'") { it.long(0) }.isEmpty())
        }
    }

    @Test fun badStoredSequenceAndNonTablePendingStateFailClosed() {
        val matched = matched()
        open().use { db ->
            val store = BackupIdentityStore(db)
            store.resetForOrdinaryRestore()
            db.execute("INSERT INTO sync_upload_state VALUES('default','device-7f3a91c2',9223372036854775807)")
            assertEquals(BackupIdentityFailure.INVALID_SEQUENCE,
                assertThrows(BackupIdentityException::class.java) { store.restoreContinuity(matched, fakeClaim) }.diagnostic)
            db.execute("DELETE FROM sync_upload_state")
            db.execute("INSERT INTO sync_upload_state VALUES('default','device-7f3a91c2',9)")
            assertEquals(BackupIdentityFailure.INVALID_SEQUENCE,
                assertThrows(BackupIdentityException::class.java) { store.restoreContinuity(matched, fakeClaim) }.diagnostic)
            db.execute("DELETE FROM sync_upload_state")
            db.execute("INSERT INTO __dstu_identity_watermark VALUES('default','peer','blob','malformed')")
            assertEquals(BackupIdentityFailure.INVALID_SEQUENCE,
                assertThrows(BackupIdentityException::class.java) { store.restoreContinuity(matched, fakeClaim) }.diagnostic)
            db.execute("DELETE FROM __dstu_identity_watermark")
            db.execute("CREATE VIEW SYNC_PENDING_PUBLICATION AS SELECT 'default' AS instance,'device-7f3a91c2' AS device")
            assertEquals(BackupIdentityFailure.CONTRACT_MISMATCH,
                assertThrows(BackupIdentityException::class.java) { store.restoreContinuity(matched, fakeClaim) }.diagnostic)
            assertTrue(store.readSnapshot("default", "device-7f3a91c2").receipts.isEmpty())
        }
    }

    @Test fun missingOrUnrelatedAuthenticatedPeerMappingRejectsWithoutChanges() {
        val matched = matched()
        open().use { db ->
            val store = BackupIdentityStore(db)
            val bad = object : BackupIdentityTestClaim() {
                override fun withVerifiedExclusiveClaim(binding: BackupIdentityClaimBinding, commit: (BackupIdentityPeerAliases) -> Unit) {
                    commit(BackupIdentityPeerAliases(mapOf("device-91bc4de0" to "unrelated-peer")))
                }
            }
            assertEquals(BackupIdentityFailure.CURSOR_ALIAS_CONFLICT,
                assertThrows(BackupIdentityException::class.java) { store.restoreContinuity(matched, bad) }.diagnostic)
            assertTrue(db.query("SELECT 1 FROM sqlite_master WHERE name='__dstu_identity_receipt'") { it.long(0) }.isEmpty())
        }
    }

    @Test fun authenticatedHashedSourceUsesCanonicalRuntimeKeysWithoutHashingTheHash() {
        val rawPeer = "device-91bc4de0"
        val hashedPeer = SyncWireCodec.deviceHash(rawPeer)
        // Synthetic sidecar: the digest and claim stand-in are test-only, not independent evidence.
        val matched = matched { it.toString(Charsets.UTF_8).replace(rawPeer, hashedPeer).toByteArray(Charsets.UTF_8) }
        open().use { db ->
            val claim = object : BackupIdentityTestClaim() {
                override fun withVerifiedExclusiveClaim(binding: BackupIdentityClaimBinding, commit: (BackupIdentityPeerAliases) -> Unit) {
                    assertEquals(listOf(hashedPeer), binding.uploaderIds)
                    commit(BackupIdentityPeerAliases(mapOf(hashedPeer to rawPeer)))
                }
            }
            val store = BackupIdentityStore(db)
            store.restoreContinuity(matched, claim)
            val snapshot = store.readSnapshot("default", "device-7f3a91c2")
            assertEquals(listOf(ContinuityCursor(hashedPeer, 12)), snapshot.sourceCursors)
            assertEquals(setOf(rawPeer, hashedPeer), snapshot.cursors.map { it.uploaderDeviceId }.toSet())
            assertFalse(snapshot.cursors.any { it.uploaderDeviceId == SyncWireCodec.deviceHash(hashedPeer) })
        }
    }

    @Test fun ordinaryResetFailureRollsBackAllConsumptionAndBindingChanges() {
        val matched = matched()
        open().use { db ->
            val store = BackupIdentityStore(db)
            store.restoreContinuity(matched, fakeClaim)
            val throwing = object : SqlDatabase by db {
                override fun <T> transaction(block: SqlOperations.() -> T): T = db.transaction {
                    val actual = this
                    val wrapper = object : SqlOperations by actual {
                        override fun execute(sql: String, args: List<SqlValue>) {
                            actual.execute(sql, args)
                            if (sql == "DELETE FROM __dstu_identity_binding") error("injected reset failure")
                        }
                    }
                    wrapper.block()
                }
            }
            assertThrows(IllegalStateException::class.java) { BackupIdentityStore(throwing).resetForOrdinaryRestore() }
            val after = store.readSnapshot("default", "device-7f3a91c2")
            assertEquals(matched.state.repository, after.binding)
            assertEquals(matched.state.cursors, after.sourceCursors)
            assertEquals(matched.state.watermarks.toSet(), after.watermarks.toSet())
            assertEquals((matched.state.processedKeys + matched.takeoverKey).toSet(), after.processedKeys.toSet())
            assertEquals(matched.takeoverKey, after.receipts.single().takeoverKey)
        }
    }

    @Test fun caseVariantTableCannotBypassExactReaderOrTriggerChecks() {
        open().use { db ->
            db.execute("CREATE TABLE __DSTU_IDENTITY_BINDING(instance_id TEXT NOT NULL PRIMARY KEY,provider TEXT NOT NULL,endpoint_hint TEXT NOT NULL)")
            val failure = assertThrows(BackupIdentityException::class.java) { BackupIdentityStore(db).resetForOrdinaryRestore() }
            assertEquals(BackupIdentityFailure.CONTRACT_MISMATCH, failure.diagnostic)
            assertTrue(db.query("SELECT 1 FROM sqlite_master WHERE name='__dstu_identity_receipt'") { it.long(0) }.isEmpty())
        }
    }

    @Test fun pendingAttemptAndIdentityShareTheSameTransaction() {
        val matched = matched()
        open().use { db ->
            db.execute("CREATE TABLE test_attempt(operation TEXT NOT NULL PRIMARY KEY,status TEXT NOT NULL)")
            db.execute("INSERT INTO test_attempt VALUES('attempt','PENDING')")
            var failLedger = true
            val claim = object : BackupIdentityClaimPort() {
                override fun withVerifiedExclusiveClaim(binding: BackupIdentityClaimBinding, commit: (BackupIdentityPeerAliases) -> Unit) {
                    commit(BackupIdentityPeerAliases(binding.uploaderIds.associateWith { it }))
                }
                override fun completePendingInTransaction(binding: BackupIdentityClaimBinding, candidate: SqlOperations) {
                    val status = candidate.query("SELECT status FROM test_attempt WHERE operation='attempt'") { it.text(0) }.single()
                    check(status == "PENDING")
                    candidate.execute("UPDATE test_attempt SET status='COMPLETED' WHERE operation='attempt'")
                    if (failLedger) error("injected ledger failure")
                }
            }
            val store = BackupIdentityStore(db)
            assertThrows(IllegalStateException::class.java) { store.restoreContinuity(matched, claim) }
            assertEquals("PENDING", db.query("SELECT status FROM test_attempt") { it.text(0) }.single())
            assertTrue(db.query("SELECT 1 FROM sqlite_master WHERE name='__dstu_identity_receipt'") { it.long(0) }.isEmpty())
            failLedger = false
            store.restoreContinuity(matched, claim)
            assertEquals("COMPLETED", db.query("SELECT status FROM test_attempt") { it.text(0) }.single())
            assertEquals(matched.takeoverKey, store.readSnapshot("default", "device-7f3a91c2").receipts.single().takeoverKey)
        }
    }

    private fun attempt(matched: ContinuityValidation.Matched) = BackupIdentityAttempt(
        operationId = "77100fb9-af3a-4167-8b60-56a1bfb881bb",
        targetInstallationId = "cc531930-8c58-4ff4-b5b1-c84db1b7353a",
        instanceId = matched.state.repository.instanceId,
        writerId = matched.state.upload.deviceId,
        archiveSha256 = matched.archiveSha256,
        sidecarSha256 = matched.sidecarSha256,
    )

    private fun authorized(
        matched: ContinuityValidation.Matched,
        claim: BackupIdentityAttempt = attempt(matched),
        aliases: Map<String, String> = matched.state.cursors.associate { it.uploaderDeviceId to it.uploaderDeviceId },
        witness: String = "a".repeat(64),
    ) = BackupIdentityTrustedComposition.authorize(claim, matched, aliases, witness)

    @Test fun authorizedStagingPersistsAndReadOnlyRestartReconcilesTheExactAttempt() {
        val matched = matched()
        val authorization = authorized(matched)
        val expected = authorization.expected
        open().use { db ->
            val store = BackupIdentityStore(db)
            assertEquals(expected, store.stageAuthorizedContinuity(matched, authorization))
            val snapshot = store.readSnapshot(expected.attempt.instanceId, expected.attempt.writerId)
            assertEquals(matched.state.repository, snapshot.binding)
            assertEquals(matched.state.upload.lastSeq, snapshot.publishedSequence)
            assertEquals(matched.state.cursors, snapshot.sourceCursors)
            assertEquals(matched.state.watermarks.toSet(), snapshot.watermarks.toSet())
            assertEquals(matched.state.publications, snapshot.publications)
            assertEquals(matched.takeoverKey, snapshot.receipts.single().takeoverKey)
            assertEquals(5L, SqlUploadState(db, "default", "device-7f3a91c2").published())
        }
        open().use { reopened ->
            val store = BackupIdentityStore(reopened)
            val readOnly = object : SqlDatabase by reopened {
                override fun <T> transaction(block: SqlOperations.() -> T): T = error("must not write during reconciliation")
            }
            BackupIdentityStore(readOnly).requireAuthorizedContinuityCommit(expected)
            val failure = assertThrows(BackupIdentityException::class.java) { store.stageAuthorizedContinuity(matched, authorization) }
            assertEquals(BackupIdentityFailure.DUPLICATE_TAKEOVER, failure.diagnostic)
            BackupIdentityStore(readOnly).requireAuthorizedContinuityCommit(expected)
        }
    }

    @Test fun authorizedStagingRejectsEveryMismatchInClaimBindingAndKeepsDatabaseUntouched() {
        val matched = matched()
        val correct = attempt(matched)
        val wrongClaims = listOf(
            correct.copy(instanceId = "other-instance"), correct.copy(writerId = "other-writer"),
            correct.copy(archiveSha256 = "b".repeat(64)), correct.copy(sidecarSha256 = "c".repeat(64)),
        )
        open().use { db ->
            val store = BackupIdentityStore(db)
            for (wrong in wrongClaims) {
                val failure = assertThrows(BackupIdentityException::class.java) { store.stageAuthorizedContinuity(matched, authorized(matched, wrong)) }
                assertEquals(BackupIdentityFailure.AUTHORIZATION_MISMATCH, failure.diagnostic)
                assertTrue(db.query("SELECT 1 FROM sqlite_master WHERE name='__dstu_identity_receipt'") { it.long(0) }.isEmpty())
            }
            val otherSnapshot = matched { String(it).replace("device-91bc4de0", "device-91bc4de1").toByteArray() }
            val failure = assertThrows(BackupIdentityException::class.java) { store.stageAuthorizedContinuity(otherSnapshot, authorized(matched)) }
            assertEquals(BackupIdentityFailure.AUTHORIZATION_MISMATCH, failure.diagnostic)
            assertTrue(db.query("SELECT 1 FROM sqlite_master WHERE name='__dstu_identity_receipt'") { it.long(0) }.isEmpty())
        }
    }

    @Test fun restartReadbackRejectsEveryAttemptOrReceiptMismatchWithoutRepairingAnything() {
        val matched = matched()
        val expected = authorized(matched).expected
        open().use { db ->
            val store = BackupIdentityStore(db)
            store.stageAuthorizedContinuity(matched, authorized(matched))
            val a = expected.attempt
            val wrong = listOf(
                expected.copy(attempt = a.copy(operationId = "953e8192-cc41-484b-8f2f-1ef03937b330")),
                expected.copy(attempt = a.copy(targetInstallationId = "cc531930-8c58-4ff4-b5b1-c84db1b7353b")),
                expected.copy(attempt = a.copy(instanceId = "other-instance")), expected.copy(attempt = a.copy(writerId = "other-writer")),
                expected.copy(attempt = a.copy(archiveSha256 = "b".repeat(64))), expected.copy(attempt = a.copy(sidecarSha256 = "c".repeat(64))),
                expected.copy(takeoverKey = "different-key"), expected.copy(witness = "d".repeat(64)),
                expected.copy(aliasesSha256 = "e".repeat(64)),
            )
            for (candidate in wrong) {
                assertEquals(BackupIdentityFailure.COMMIT_MISMATCH,
                    assertThrows(BackupIdentityException::class.java) { store.requireAuthorizedContinuityCommit(candidate) }.diagnostic)
            }
            db.execute("DELETE FROM __dstu_identity_receipt")
            assertEquals(BackupIdentityFailure.COMMIT_MISMATCH,
                assertThrows(BackupIdentityException::class.java) { store.requireAuthorizedContinuityCommit(expected) }.diagnostic)
            assertTrue(db.query("SELECT 1 FROM __dstu_identity_receipt") { it.long(0) }.isEmpty())
        }
    }

    @Test fun preexistingArchiveReceiptAndFakeCompletionNeverSkipTheTransaction() {
        val matched = matched()
        val authorization = authorized(matched)
        val expected = authorization.expected
        open().use { db ->
            val store = BackupIdentityStore(db)
            store.resetForOrdinaryRestore()
            db.execute("INSERT INTO __dstu_identity_receipt VALUES(?,?,?,?)", listOf(SqlValue.Text(expected.takeoverKey),
                SqlValue.Text(expected.attempt.instanceId), SqlValue.Text(expected.attempt.writerId), SqlValue.Text(expected.attempt.sidecarSha256)))
            assertEquals(BackupIdentityFailure.DUPLICATE_TAKEOVER,
                assertThrows(BackupIdentityException::class.java) { store.stageAuthorizedContinuity(matched, authorization) }.diagnostic)
            assertEquals(BackupIdentityFailure.COMMIT_MISMATCH,
                assertThrows(BackupIdentityException::class.java) { store.requireAuthorizedContinuityCommit(expected) }.diagnostic)
            db.execute("DELETE FROM __dstu_identity_receipt")
            db.execute("INSERT INTO __dstu_identity_commit VALUES(?,?,?,?,?,?,?,?,?)", listOf(
                SqlValue.Text(expected.attempt.operationId), SqlValue.Text(expected.attempt.targetInstallationId),
                SqlValue.Text(expected.attempt.instanceId), SqlValue.Text(expected.attempt.writerId),
                SqlValue.Text(expected.attempt.archiveSha256), SqlValue.Text(expected.attempt.sidecarSha256),
                SqlValue.Text(expected.takeoverKey), SqlValue.Text("0".repeat(64)), SqlValue.Text(expected.aliasesSha256),
            ))
            assertEquals(BackupIdentityFailure.DUPLICATE_TAKEOVER,
                assertThrows(BackupIdentityException::class.java) { store.stageAuthorizedContinuity(matched, authorization) }.diagnostic)
            db.execute("INSERT INTO __dstu_identity_receipt VALUES(?,?,?,?)", listOf(SqlValue.Text(expected.takeoverKey),
                SqlValue.Text(expected.attempt.instanceId), SqlValue.Text(expected.attempt.writerId), SqlValue.Text(expected.attempt.sidecarSha256)))
            assertEquals(BackupIdentityFailure.COMMIT_MISMATCH,
                assertThrows(BackupIdentityException::class.java) { store.requireAuthorizedContinuityCommit(expected) }.diagnostic)
            // Even if every fake field happened to match, staging itself cannot skip the transaction.
            val digest = hash(("dstu-continuity-commit-v1\u0000" + expected.witness).toByteArray())
            db.execute("UPDATE __dstu_identity_commit SET witness_sha256=?", listOf(SqlValue.Text(digest)))
            assertEquals(BackupIdentityFailure.DUPLICATE_TAKEOVER,
                assertThrows(BackupIdentityException::class.java) { store.stageAuthorizedContinuity(matched, authorization) }.diagnostic)
            assertNull(store.readSnapshot("default", "device-7f3a91c2").binding)
        }
    }

    @Test fun stagingWriteAndReadbackFaultsRollbackTheWholeIdentityTransaction() {
        val matched = matched()
        val authorization = authorized(matched)
        for (fault in listOf("receipt", "commit", "readback")) {
            root.mkdirs()
            open().use { db ->
                val throwing = object : SqlDatabase by db {
                    override fun <T> transaction(block: SqlOperations.() -> T): T = db.transaction {
                        val actual = this
                        val wrapper = object : SqlOperations by actual {
                            override fun execute(sql: String, args: List<SqlValue>) {
                                actual.execute(sql, args)
                                if ((fault == "receipt" && sql.startsWith("INSERT INTO __dstu_identity_receipt")) ||
                                    (fault == "commit" && sql.startsWith("INSERT INTO __dstu_identity_commit"))) error("injected write failure")
                            }
                            override fun <T> query(sql: String, args: List<SqlValue>, map: (com.deepstudent.platform.database.SqlRow) -> T): List<T> {
                                val result = actual.query(sql, args, map)
                                if (fault == "readback" && sql.startsWith("SELECT 1 FROM __dstu_identity_commit WHERE operation_id=? AND")) error("injected read failure")
                                return result
                            }
                        }
                        wrapper.block()
                    }
                }
                assertThrows(IllegalStateException::class.java) { BackupIdentityStore(throwing).stageAuthorizedContinuity(matched, authorization) }
                assertTrue(db.query("SELECT 1 FROM sqlite_master WHERE name='__dstu_identity_receipt'") { it.long(0) }.isEmpty())
                BackupIdentityStore(db).stageAuthorizedContinuity(matched, authorization)
                BackupIdentityStore(db).requireAuthorizedContinuityCommit(authorization.expected)
            }
            root.deleteRecursively()
        }
    }

    @Test fun ordinaryRestoreClearsCompletionStampButKeepsDuplicateReceiptAndUploadHistory() {
        val matched = matched()
        val auth = authorized(matched)
        open().use { db ->
            val store = BackupIdentityStore(db)
            store.stageAuthorizedContinuity(matched, auth)
            store.resetForOrdinaryRestore()
            val snapshot = store.readSnapshot(auth.expected.attempt.instanceId, auth.expected.attempt.writerId)
            assertNull(snapshot.binding)
            assertEquals(matched.state.upload.lastSeq, snapshot.publishedSequence)
            assertEquals(matched.takeoverKey, snapshot.receipts.single().takeoverKey)
            assertTrue(snapshot.cursors.isEmpty())
            assertEquals(BackupIdentityFailure.COMMIT_MISMATCH,
                assertThrows(BackupIdentityException::class.java) { store.requireAuthorizedContinuityCommit(auth.expected) }.diagnostic)
            assertEquals(BackupIdentityFailure.DUPLICATE_TAKEOVER,
                assertThrows(BackupIdentityException::class.java) { store.stageAuthorizedContinuity(matched, auth) }.diagnostic)
        }
    }

    @Test fun authorizationCopiesAliasesAndRejectsInvalidWitnessOrNoncanonicalIds() {
        val matched = matched()
        val aliases = matched.state.cursors.associate { it.uploaderDeviceId to it.uploaderDeviceId }.toMutableMap()
        val auth = authorized(matched, aliases = aliases)
        aliases.clear()
        assertEquals(64, BackupIdentityTrustedComposition.newWitness().length)
        assertEquals(BackupIdentityFailure.AUTHORIZATION_MISMATCH,
            assertThrows(BackupIdentityException::class.java) { authorized(matched, witness = "not-a-witness") }.diagnostic)
        assertEquals(BackupIdentityFailure.AUTHORIZATION_MISMATCH,
            assertThrows(BackupIdentityException::class.java) { authorized(matched, attempt(matched).copy(operationId = "bad-id")) }.diagnostic)
        open().use { db ->
            val store = BackupIdentityStore(db)
            store.stageAuthorizedContinuity(matched, auth)
            store.requireAuthorizedContinuityCommit(auth.expected)
        }
    }

    @Test fun matchedEveryFieldIsRecheckedInsideTheStagingTransaction() {
        val matched = matched()
        val auth = authorized(matched)
        val state = matched.state
        // Java reflection deliberately bypasses Kotlin's internal Matched/State constructors. This
        // verifies the data bridge does not merely compare hashes when handed a different object.
        val stateConstructor = ContinuityState::class.java.declaredConstructors.single()
        fun stateWith(index: Int, value: Any): ContinuityState {
            val values: Array<Any> = arrayOf(state.repository, state.upload, state.cursors, state.watermarks, state.publications, state.processedKeys)
            values[index] = value
            return stateConstructor.newInstance(*values) as ContinuityState
        }
        val stateVariants = listOf(
            stateWith(0, state.repository.copy(endpointHint = "other")),
            stateWith(1, state.upload.copy(lastSeq = state.upload.lastSeq + 1)),
            stateWith(2, state.cursors + ContinuityCursor("peer-test", 1)),
            stateWith(3, state.watermarks + ContinuityWatermark("peer-test", "blob", 1)),
            stateWith(4, state.publications + ContinuityPublication(state.upload.deviceId, "blob", "other", 1)),
            stateWith(5, state.processedKeys + "other"),
        )
        val matchedConstructor = ContinuityValidation.Matched::class.java.declaredConstructors.single()
        fun changed(index: Int, value: Any): ContinuityValidation.Matched {
            val values: Array<Any> = arrayOf(matched.snapshotEpoch, matched.archiveSha256, matched.sidecarSha256,
                matched.sourceInstallation, matched.takeoverKey, state)
            values[index] = value
            return matchedConstructor.newInstance(*values) as ContinuityValidation.Matched
        }
        val variants = listOf(
            changed(0, "other-epoch"), changed(1, "b".repeat(64)), changed(2, "c".repeat(64)),
            changed(3, matched.sourceInstallation.copy(versionCode = matched.sourceInstallation.versionCode + 1)),
            changed(4, "other-key"),
        ) + stateVariants.map { changed(5, it) }
        open().use { db ->
            val store = BackupIdentityStore(db)
            for (variant in variants) {
                assertEquals(BackupIdentityFailure.AUTHORIZATION_MISMATCH,
                    assertThrows(BackupIdentityException::class.java) { store.stageAuthorizedContinuity(variant, auth) }.diagnostic)
                assertTrue(db.query("SELECT 1 FROM sqlite_master WHERE name='__dstu_identity_receipt'") { it.long(0) }.isEmpty())
            }
            store.stageAuthorizedContinuity(matched, auth)
        }
    }

    @Test fun copiedArchiveCannotSilentlyAdvanceTheAuthorizedIdentityBaseline() {
        val matched = matched()
        val auth = authorized(matched)
        open().use { db ->
            val store = BackupIdentityStore(db)
            store.resetForOrdinaryRestore()
            db.execute("INSERT INTO __dstu_identity_watermark VALUES('default','device-91bc4de0','blob',1000)")
            assertEquals(BackupIdentityFailure.APPLIED_STATE_MISMATCH,
                assertThrows(BackupIdentityException::class.java) { store.stageAuthorizedContinuity(matched, auth) }.diagnostic)
            assertEquals(1000L, db.query("SELECT last_applied_offset FROM __dstu_identity_watermark") { it.long(0) }.single())
            assertTrue(db.query("SELECT 1 FROM __dstu_identity_receipt") { it.long(0) }.isEmpty())
            db.execute("DELETE FROM __dstu_identity_watermark")
            db.execute("INSERT INTO __dstu_identity_legacy_key VALUES('default','unapproved-extra')")
            assertEquals(BackupIdentityFailure.APPLIED_STATE_MISMATCH,
                assertThrows(BackupIdentityException::class.java) { store.stageAuthorizedContinuity(matched, auth) }.diagnostic)
            db.execute("DELETE FROM __dstu_identity_legacy_key")
            db.execute("INSERT INTO __dstu_identity_publication VALUES('default','device-7f3a91c2','blob','unapproved',99)")
            assertEquals(BackupIdentityFailure.APPLIED_STATE_MISMATCH,
                assertThrows(BackupIdentityException::class.java) { store.stageAuthorizedContinuity(matched, auth) }.diagnostic)
            db.execute("DELETE FROM __dstu_identity_publication")
            store.stageAuthorizedContinuity(matched, auth)
            store.requireAuthorizedContinuityCommit(auth.expected)
        }
    }

    @Test fun authorizedAliasFailureRollsBackAndReconciliationPropagatesReadFailure() {
        val matched = matched()
        val auth = authorized(matched)
        open().use { db ->
            val store = BackupIdentityStore(db)
            val invalid = authorized(matched, aliases = emptyMap())
            assertEquals(BackupIdentityFailure.CURSOR_ALIAS_CONFLICT,
                assertThrows(BackupIdentityException::class.java) { store.stageAuthorizedContinuity(matched, invalid) }.diagnostic)
            assertTrue(db.query("SELECT 1 FROM sqlite_master WHERE name='__dstu_identity_receipt'") { it.long(0) }.isEmpty())
            store.stageAuthorizedContinuity(matched, auth)
            val unreadable = object : SqlDatabase by db {
                override fun <T> readSnapshot(block: com.deepstudent.platform.database.SqlReadOperations.() -> T): T = error("injected read failure")
            }
            assertThrows(IllegalStateException::class.java) { BackupIdentityStore(unreadable).requireAuthorizedContinuityCommit(auth.expected) }
            store.requireAuthorizedContinuityCommit(auth.expected)
        }
    }

    @Test fun concurrentAuthorizedStagesCommitAtMostOnce() {
        val matched = matched()
        val auth = authorized(matched)
        open().use { db ->
            val gate = java.util.concurrent.CountDownLatch(1)
            val results = java.util.Collections.synchronizedList(mutableListOf<String>())
            val threads = (1..2).map {
                Thread {
                    gate.await()
                    try {
                        BackupIdentityStore(db).stageAuthorizedContinuity(matched, auth)
                        results.add("committed")
                    } catch (failure: BackupIdentityException) {
                        results.add(failure.diagnostic.name)
                    }
                }.apply { start() }
            }
            gate.countDown()
            threads.forEach { it.join(30_000); assertFalse(it.isAlive) }
            assertEquals(listOf("DUPLICATE_TAKEOVER", "committed"), results.sorted())
            BackupIdentityStore(db).requireAuthorizedContinuityCommit(auth.expected)
        }
    }

    @Test fun authorizedTraceUsesOperationCorrelationWithoutLeakingSnapshotOrWitness() {
        val matched = matched()
        val auth = authorized(matched)
        open().use { db ->
            val events = mutableListOf<BackupIdentityLogEvent>()
            val store = BackupIdentityStore(db, BackupIdentityLog(events::add))
            store.stageAuthorizedContinuity(matched, auth)
            store.requireAuthorizedContinuityCommit(auth.expected)
            assertEquals(listOf(BackupIdentityStatus.STARTED, BackupIdentityStatus.COMMITTED,
                BackupIdentityStatus.STARTED, BackupIdentityStatus.VERIFIED), events.map { it.status })
            assertTrue(events.all { it.correlationId == auth.expected.attempt.operationId && it.owner == "services/backup/data" && it.elapsedMillis >= 0 })
            assertFalse(events.toString().contains(auth.expected.witness))
            assertFalse(events.toString().contains(auth.expected.attempt.sidecarSha256))
            assertFalse(events.toString().contains(auth.expected.attempt.writerId))
        }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
