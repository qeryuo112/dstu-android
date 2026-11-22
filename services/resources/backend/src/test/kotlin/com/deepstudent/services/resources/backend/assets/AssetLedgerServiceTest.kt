package com.deepstudent.services.resources.backend.assets

import com.deepstudent.core.common.id.DeviceId
import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.time.MonotonicClock
import com.deepstudent.services.resources.api.AssetDigest
import com.deepstudent.services.resources.api.AssetKey
import com.deepstudent.services.resources.api.assets.*
import org.junit.Assert.*
import org.junit.Test

class AssetLedgerServiceTest {
    private val bytes = "secret bytes must not appear in logs".toByteArray()
    private val target = AssetTarget(AssetKey.parse("active/chat_attachments/private/path"),
        AssetDigest(Digests.sha256Hex(bytes), bytes.size.toLong()))
    private val records = mutableListOf<LogRecord>()
    private val deletions = AssetDeletionReader { AssetDeletionSnapshot(
        listOf(QueuedAssetTombstone(target.key.value, target.digest.size, "2026-11-20T12:00:00Z", 1)),
        listOf(AssetJournalEntry("id-1", target.key.value, AssetJournalState.READY),
            AssetJournalEntry("id-2", "active/anki_media/other", AssetJournalState.PREPARED)),
    ) }
    private val scratch = AssetScratchCleaner { AssetCleanupResult(AssetCleanupPolicy.REMOVE_S208_OWN_SCRATCH, 1) }
    private val service = AssetLedgerService(deletions, listOf(FakeReader()), scratch, object : Logger {
        override fun log(record: LogRecord) { records += record }
    }, MonotonicClock { System.nanoTime() })

    @Test fun multipleRecoverableReferencesNeverGrantReclamation() {
        val report = service.references(target)
        assertEquals(2L, report.counts.single().active)
        assertEquals(1L, report.counts.single().recoverable)
        assertFalse(report.completeForReclamation)
        assertTrue(AssetReferenceOwner.CHAT_ATTACHMENTS in report.unobservedOwners)
        assertTrue(AssetReferenceOwner.REMOTE_MANIFESTS in report.unobservedOwners)
        assertEquals(AssetCleanupPolicy.RETAIN_SHARED_ASSET, service.cleanupAsset(target).policy)
        assertEquals(AssetCleanupPolicy.RETAIN_SHARED_BLOB, service.cleanupBlob(target.digest.sha256).policy)
        assertEquals(1, service.cleanupOwnScratch().removed)
    }

    @Test fun derivesOnlyReadyOutboxAndNeverPreparedJournalRows() {
        val snapshot = service.deletionSnapshot()
        assertEquals(2, snapshot.journal.size)
        val tombstones = service.deriveReadyTombstones(DeviceId.parse("device-a"))
        assertEquals(setOf(target.key.value), tombstones.entries.keys)
        assertEquals(target.digest.size.toULong(), tombstones.entries.getValue(target.key.value).size)
        assertEquals("device-a", tombstones.entries.getValue(target.key.value).deviceId)
    }

    @Test fun unownedOrMalformedReadyEntriesAreNotSilentlyPublished() {
        val invalidRows = listOf(
            QueuedAssetTombstone("active/notes_assets/not-owned", 3, "t", 0),
            QueuedAssetTombstone(target.key.value, -1, "t", 0),
            QueuedAssetTombstone(target.key.value, 3, "", 0),
            QueuedAssetTombstone(target.key.value, 3, "t", -1),
        )
        for (row in invalidRows) {
            val bad = AssetLedgerService({ AssetDeletionSnapshot(listOf(row), emptyList()) }, emptyList(), scratch)
            assertThrows(Exception::class.java) { bad.deriveReadyTombstones(DeviceId.parse("device-a")) }
        }
        val duplicate = QueuedAssetTombstone(target.key.value, 3, "t", 0)
        val repeated = AssetLedgerService({ AssetDeletionSnapshot(listOf(duplicate, duplicate), emptyList()) }, emptyList(), scratch)
        assertThrows(IllegalArgumentException::class.java) { repeated.deriveReadyTombstones(DeviceId.parse("device-a")) }
    }

    @Test fun logsAreCorrelatedTimedAndRedactedEvenOnFailure() {
        service.references(target)
        try { service.cleanupBlob("bad/private/id") } catch (_: IllegalArgumentException) { }
        assertEquals(2, records.size)
        assertTrue(records.all { it.correlationId != null })
        assertEquals("failed", (records.last().fields["state"] as com.deepstudent.core.common.json.JsonValue.Text).value)
        for (record in records) {
            assertNotNull(record.fields["owner"])
            assertNotNull(record.fields["phase"])
            assertNotNull(record.fields["state"])
            assertNotNull(record.fields["elapsedMs"])
            assertNotNull(record.fields["outcome"])
            val rendered = record.toString()
            assertFalse(rendered.contains(target.key.value))
            assertFalse(rendered.contains(target.digest.sha256))
            assertFalse(rendered.contains("private"))
            assertFalse(rendered.contains(String(bytes)))
        }
        println("S2.08 LOG SAMPLE: ${records.first()}")
    }

    @Test fun scanFailureLogsOnlyFixedAllowlistedReason() {
        val scanner = AssetScanner { throw AssetScanException(AssetScanFailure.LIMIT) }
        val failing = AssetLedgerService(deletions, emptyList(), scratch, object : Logger {
            override fun log(record: LogRecord) { records += record }
        }, scanner = scanner)
        assertThrows(AssetScanException::class.java) { failing.scan() }
        assertEquals("LIMIT", (records.single().fields["outcome"] as com.deepstudent.core.common.json.JsonValue.Text).value)
        assertNotNull(records.single().fields["elapsedMs"])
    }

    @Test fun duplicateOrLyingOwnerRegistrationIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AssetLedgerService(deletions, listOf(FakeReader(), FakeReader()), scratch)
        }
        val bad = object : AssetReferenceReader {
            override val owner = AssetReferenceOwner.VFS_FILES
            override fun inspect(target: AssetTarget) = AssetReferenceCount(AssetReferenceOwner.CHAT_ATTACHMENTS, 0, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AssetLedgerService(deletions, listOf(bad), scratch).references(target)
        }
    }
}

private class FakeReader : AssetReferenceReader {
    override val owner = AssetReferenceOwner.VFS_FILES
    override fun inspect(target: AssetTarget) = AssetReferenceCount(owner, 2, 1)
}
