package com.deepstudent.app.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deepstudent.services.backup.backend.ContinuityGrantException
import com.deepstudent.services.backup.backend.ContinuityGrantFailure
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.SyncFailure
import java.security.KeyPairGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedContinuityDeviceTest {
    @get:Rule val foreground = BackupForegroundRule()

    @Test fun realTlsRepositoryRetiresOldWriterAndMigratedWriterPublishesNextSequence() = runBlocking {
        withRepository { repo, fixture ->
            val coordinator = fixture.coordinator().also { it.beforeBusinessOpen() }
            val first = fixture.reviewAndQueue(coordinator)
            assertEquals(0, repo.claimCount())
            assertEquals(fixture.oldWriter, fixture.writer())
            val reopened = fixture.coordinator().also { it.beforeBusinessOpen() }
            assertEquals(BackupRestoreUiStatus.APPLIED_CONTINUITY, reopened.status.value)
            assertEquals(ManagedContinuityTestRepository.WRITER, fixture.writer())
            val second = reopened.issueContinuityTarget()
            assertEquals(first.installationId, second.installationId)
            assertNotEquals(first.operationId, second.operationId)
            assertEquals(1, repo.claimCount())
            fixture.checkRestoredContent()
            fixture.publishNextThroughRealPipeline()
            fixture.checkIndependentReaderConsumedFiveAndSix()
        }
    }

    @Test fun staleRemoteSequenceStopsBeforeClaimAndKeepsTheOriginalSlot() = runBlocking {
        withRepository { repo, fixture ->
            fixture.reviewAndQueue(fixture.coordinator().also { it.beforeBusinessOpen() })
            repo.simulateSequenceDrift()
            val reopened = fixture.coordinator().also { it.beforeBusinessOpen() }
            assertEquals(BackupRestoreUiStatus.CONTINUITY_FAILED, reopened.status.value)
            assertEquals(0, repo.claimCount())
            fixture.assertUnchanged()
        }
    }

    @Test fun duplicateRemoteWriterSequenceRejectsBeforeCreatingAClaim() = runBlocking {
        withRepository { repo, fixture ->
            fixture.reviewAndQueue(fixture.coordinator().also { it.beforeBusinessOpen() })
            repo.simulateSequenceConflict()
            val denied = fixture.coordinator().also { it.beforeBusinessOpen() }
            assertEquals(BackupRestoreUiStatus.CONTINUITY_FAILED, denied.status.value)
            assertEquals(0, repo.claimCount())
            fixture.assertUnchanged()
        }
    }

    @Test fun duplicateSequenceAfterRemoteClaimRetainsTheUncertainAttemptOnReopen() = runBlocking {
        withRepository { repo, fixture ->
            val operation = fixture.reviewAndQueue(fixture.coordinator().also { it.beforeBusinessOpen() })
            repo.introduceConflictAfterClaim()
            val denied = fixture.coordinator().also { it.beforeBusinessOpen() }
            assertEquals(BackupRestoreUiStatus.NEEDS_REVIEW, denied.status.value)
            assertEquals(1, repo.claimCount())
            val evidence = fixture.pendingEvidence(operation.operationId, confirmedClaim = false)
            fixture.assertUnchanged()
            val reopened = fixture.coordinator().also { it.beforeBusinessOpen() }
            assertEquals(BackupRestoreUiStatus.NEEDS_REVIEW, reopened.status.value)
            assertEquals(evidence, fixture.pendingEvidence(operation.operationId, confirmedClaim = false))
            fixture.assertUnchanged()
        }
    }

    @Test fun expiredAfterConfirmedClaimBeforeActivationRetainsEvidenceAndNeverBypassesExpiry() = runBlocking {
        withRepository { repo, fixture ->
            val operation = fixture.reviewAndQueue(fixture.coordinator().also { it.beforeBusinessOpen() })
            fixture.expireAfterClaimCheckpoint = true
            val interrupted = fixture.coordinator().also { it.beforeBusinessOpen() }
            assertEquals(BackupRestoreUiStatus.NEEDS_REVIEW, interrupted.status.value)
            assertEquals(1, repo.claimCount())
            val evidence = fixture.pendingEvidence(operation.operationId, confirmedClaim = true)
            fixture.assertUnchanged()
            fixture.expireAfterClaimCheckpoint = false
            fixture.now = fixture.now.plusSeconds(3_600)
            val reopened = fixture.coordinator().also { it.beforeBusinessOpen() }
            assertEquals(BackupRestoreUiStatus.NEEDS_REVIEW, reopened.status.value)
            assertThrows(IllegalStateException::class.java) { reopened.requireStableSyncIdentity() }
            assertEquals(evidence, fixture.pendingEvidence(operation.operationId, confirmedClaim = true))
            fixture.assertUnchanged()
        }
    }

    @Test fun deterministicCandidateFailureAfterClaimRetainsCheckpointAndRetriesSameClaim() = runBlocking {
        withRepository { repo, fixture ->
            val operation = fixture.reviewAndQueue(fixture.coordinator().also { it.beforeBusinessOpen() })
            fixture.failAfterClaimCheckpoint = true
            val interrupted = fixture.coordinator().also { it.beforeBusinessOpen() }
            assertEquals(BackupRestoreUiStatus.NEEDS_REVIEW, interrupted.status.value)
            assertEquals(1, repo.claimCount())
            val evidence = fixture.pendingEvidence(operation.operationId, confirmedClaim = true)
            fixture.assertUnchanged()
            val checked = fixture.coordinator().also { it.beforeBusinessOpen() }
            assertEquals(BackupRestoreUiStatus.NEEDS_REVIEW, checked.status.value)
            assertEquals(evidence, fixture.pendingEvidence(operation.operationId, confirmedClaim = true))
            fixture.failAfterClaimCheckpoint = false
            val resumed = fixture.coordinator().also { it.beforeBusinessOpen() }
            assertEquals(BackupRestoreUiStatus.APPLIED_CONTINUITY, resumed.status.value)
            assertEquals(1, repo.claimCount())
            assertEquals(ManagedContinuityTestRepository.WRITER, fixture.writer())
            fixture.checkRestoredContent()
        }
    }

    @Test fun lostClaimResponseRetainsIntentAndRetriesTheExactOperation() = runBlocking {
        withRepository { repo, fixture ->
            val operation = fixture.reviewAndQueue(fixture.coordinator().also { it.beforeBusinessOpen() })
            repo.loseNextClaimReply()
            val interrupted = fixture.coordinator().also { it.beforeBusinessOpen() }
            assertEquals(BackupRestoreUiStatus.NEEDS_REVIEW, interrupted.status.value)
            assertEquals(1, repo.claimCount())
            fixture.pendingEvidence(operation.operationId, confirmedClaim = false)
            fixture.assertUnchanged()
            val resumed = fixture.coordinator().also { it.beforeBusinessOpen() }
            assertEquals(BackupRestoreUiStatus.APPLIED_CONTINUITY, resumed.status.value)
            assertEquals(1, repo.claimCount())
            assertEquals(ManagedContinuityTestRepository.WRITER, fixture.writer())
            fixture.checkRestoredContent()
        }
    }

    @Test fun lostClaimResponseThenExpiryKeepsUnconfirmedIntentWithoutActivating() = runBlocking {
        withRepository { repo, fixture ->
            val operation = fixture.reviewAndQueue(fixture.coordinator().also { it.beforeBusinessOpen() })
            repo.loseNextClaimReply()
            val interrupted = fixture.coordinator().also { it.beforeBusinessOpen() }
            assertEquals(BackupRestoreUiStatus.NEEDS_REVIEW, interrupted.status.value)
            assertEquals(1, repo.claimCount())
            val evidence = fixture.pendingEvidence(operation.operationId, confirmedClaim = false)
            fixture.now = fixture.now.plusSeconds(3_600)
            val reopened = fixture.coordinator().also { it.beforeBusinessOpen() }
            assertEquals(BackupRestoreUiStatus.NEEDS_REVIEW, reopened.status.value)
            assertThrows(IllegalStateException::class.java) { reopened.requireStableSyncIdentity() }
            assertEquals(evidence, fixture.pendingEvidence(operation.operationId, confirmedClaim = false))
            fixture.assertUnchanged()
        }
    }

    @Test fun anotherInstallationCannotTakeOverTheSameWriter() = runBlocking {
        withRepository { repo, first ->
            first.reviewAndQueue(first.coordinator().also { it.beforeBusinessOpen() })
            val committed = first.coordinator().also { it.beforeBusinessOpen() }
            assertEquals(BackupRestoreUiStatus.APPLIED_CONTINUITY, committed.status.value)
            ManagedContinuityScenario(repo, signingKey()).use { second ->
                second.seedTargetBeforeRestore()
                second.reviewAndQueue(second.coordinator().also { it.beforeBusinessOpen() })
                val denied = second.coordinator().also { it.beforeBusinessOpen() }
                assertEquals(BackupRestoreUiStatus.CONTINUITY_FAILED, denied.status.value)
                assertEquals(1, repo.claimCount())
                second.assertUnchanged()
            }
        }
    }

    @Test fun afterSlotCommitOnlyTheExistingReceiptCanFinishPublicationEvenIfGrantExpired() = runBlocking {
        withRepository { repo, fixture ->
            fixture.reviewAndQueue(fixture.coordinator().also { it.beforeBusinessOpen() })
            fixture.breakPublication = true
            val interrupted = fixture.coordinator().also { it.beforeBusinessOpen() }
            assertEquals(BackupRestoreUiStatus.NEEDS_REVIEW, interrupted.status.value)
            assertThrows(IllegalStateException::class.java) { interrupted.requireStableSyncIdentity() }
            assertEquals(fixture.oldWriter, fixture.writer())
            assertEquals(1, repo.claimCount())
            fixture.breakPublication = false
            fixture.now = fixture.now.plusSeconds(3_600)
            val resumed = fixture.coordinator().also { it.beforeBusinessOpen() }
            assertEquals(BackupRestoreUiStatus.APPLIED_CONTINUITY, resumed.status.value)
            assertEquals(ManagedContinuityTestRepository.WRITER, fixture.writer())
            assertEquals(1, repo.claimCount())
            fixture.checkRestoredContent()
        }
    }

    @Test fun expiredGrantOnAnUncommittedAttemptNeverWritesTheRepository() = runBlocking {
        withRepository { repo, fixture ->
            fixture.reviewAndQueue(fixture.coordinator().also { it.beforeBusinessOpen() })
            fixture.now = fixture.now.plusSeconds(3_600)
            val denied = fixture.coordinator().also { it.beforeBusinessOpen() }
            assertEquals(BackupRestoreUiStatus.CONTINUITY_FAILED, denied.status.value)
            assertEquals(0, repo.claimCount())
            fixture.assertUnchanged()
        }
    }

    @Test fun alteredSignatureCannotEvenPrepareAQueue() = runBlocking {
        withRepository { repo, fixture ->
            val coordinator = fixture.coordinator().also { it.beforeBusinessOpen() }
            val files = fixture.grant(coordinator.issueContinuityTarget())
            files.signature[0] = (files.signature[0].toInt() xor 1).toByte()
            val error = try {
                coordinator.reviewContinuity(fixture.sourceUri, files)
                error("modified signature was accepted")
            } catch (failure: ContinuityGrantException) { failure }
            assertEquals(ContinuityGrantFailure.INVALID_SIGNATURE, error.reason)
            assertEquals(BackupRestoreUiStatus.IDLE, coordinator.status.value)
            assertEquals(0, repo.claimCount())
            fixture.assertUnchanged()
        }
    }

    private suspend fun withRepository(test: suspend (ManagedContinuityTestRepository, ManagedContinuityScenario) -> Unit) {
        ManagedContinuityTestRepository().use { repo ->
            val source = repo.providerFactory.open(repo.url, ManagedContinuityTestRepository.USER, "",
                ManagedContinuityTestRepository.OLD_PASSWORD.toCharArray())
            assertNotNull(source.get("data_governance/instance.json"))
            repo.revokeOldAndRecordSequence()
            assertSourceRetired(repo)
            ManagedContinuityScenario(repo, signingKey()).use { fixture ->
                fixture.seedTargetBeforeRestore()
                test(repo, fixture)
            }
        }
    }

    private suspend fun assertSourceRetired(repo: ManagedContinuityTestRepository) {
        val old = repo.providerFactory.open(repo.url, ManagedContinuityTestRepository.USER, "",
            ManagedContinuityTestRepository.OLD_PASSWORD.toCharArray())
        val error = try {
            old.put("data_governance/retired-writer-fixture", byteArrayOf(1))
            error("retired account could still write")
        } catch (failure: SyncFailure) { failure }
        assertEquals(SyncError.AUTHENTICATION, error.error)
    }

    private fun signingKey() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
}
