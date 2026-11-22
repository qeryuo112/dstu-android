package com.deepstudent.app.backup

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContinuityRestoreJournalTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun challengeStaysStableUntilCompletionAndDoesNotChangeInstallationOnNextRequest() {
        val root = File(directory.root, "journal")
        val journal = ContinuityRestoreJournal(root)
        val first = journal.issueTarget()
        assertEquals(first, ContinuityRestoreJournal(root).issueTarget())
        journal.markCompleted(first.operationId)
        val next = ContinuityRestoreJournal(root).issueTarget()
        assertEquals(first.installationId, next.installationId)
        assertNotEquals(first.operationId, next.operationId)
        assertEquals(next, journal.issueTarget())
    }

    @Test fun savedEvidenceAndWitnessCanBeReadOnlyForTheSameOperation() {
        val root = File(directory.root, "journal")
        val journal = ContinuityRestoreJournal(root)
        val target = journal.issueTarget()
        val files = ContinuityFiles(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3), byteArrayOf(4), "a".repeat(64))
        val repo = PairedContinuityRepository("https://repository.invalid/root/", "example")
        val witness = ContinuityClaimCheckpoint(300, "b".repeat(64), "c".repeat(64))
        journal.persist(StoredContinuityEvidence(files, target, repo))
        journal.markClaimed(witness)
        val cold = ContinuityRestoreJournal(root)
        val found = cold.load(target.operationId)
        assertEquals(target, found.target)
        assertEquals(repo, found.repository)
        assertArrayEquals(files.signature, found.files.signature)
        assertEquals(witness, cold.claimed())
        assertThrows(IllegalStateException::class.java) { cold.load(target.installationId) }
        cold.cleanEvidence()
        assertEquals(target, journal.issueTarget())
        assertNull(cold.claimed())
        assertThrows(Exception::class.java) { journal.load(target.operationId) }
    }

    @Test fun uncertainClaimIntentPersistsForOnlyTheExactOperationAndEvidence() {
        val root = File(directory.root, "intent")
        val journal = ContinuityRestoreJournal(root)
        val target = journal.issueTarget()
        val digest = "a".repeat(64)
        assertFalse(journal.mightHaveClaim(target.operationId, digest))
        journal.markClaimIntent(target.operationId, digest)
        journal.markClaimIntent(target.operationId, digest)
        val reopened = ContinuityRestoreJournal(root)
        assertTrue(reopened.mightHaveClaim(target.operationId, digest))
        assertNull(reopened.claimed()) // Intent does not falsely certify a successful remote claim.
        assertThrows(IllegalStateException::class.java) { reopened.mightHaveClaim(target.operationId, "b".repeat(64)) }
        assertThrows(IllegalStateException::class.java) { reopened.markClaimIntent(target.operationId, "b".repeat(64)) }
        reopened.cleanEvidence()
        assertFalse(reopened.mightHaveClaim(target.operationId, digest))
    }

}
