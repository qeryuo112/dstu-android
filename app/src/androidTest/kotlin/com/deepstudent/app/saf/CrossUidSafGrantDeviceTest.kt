package com.deepstudent.app.saf

import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.storage.MainStorageResources
import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.time.SystemMonotonicClock
import com.deepstudent.services.resources.api.AssetDigest
import com.deepstudent.services.resources.api.DocumentCancellation
import com.deepstudent.services.resources.api.DocumentCleanup
import com.deepstudent.services.resources.api.DocumentExportResult
import com.deepstudent.services.resources.api.DocumentExportSource
import com.deepstudent.services.resources.api.DocumentFailure
import com.deepstudent.services.resources.api.DocumentImportResult
import com.deepstudent.services.resources.api.DocumentPhase
import com.deepstudent.services.resources.api.DocumentProgress
import com.deepstudent.services.resources.api.DocumentProgressObserver
import com.deepstudent.services.resources.api.StreamAttachmentRequest
import com.deepstudent.services.resources.backend.DocumentTransfer
import com.deepstudent.services.resources.data.saf.AndroidDocumentPicker
import com.deepstudent.services.resources.data.saf.AndroidDocumentSelection
import com.deepstudent.services.resources.data.saf.DocumentSelectionFailure
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * Real cross-UID grants supplied by the separate resource-test APK's own Activity. That Activity
 * is NOT DocumentsUI; these cases establish URI grant/PFD semantics, not picker provenance.
 * Happy paths exercise MainStorageResources and the accepted attachment store; failure-focused
 * cleanup cases also exercise the transfer port directly with a synthetic producer.
 */
@RunWith(AndroidJUnit4::class)
class CrossUidSafGrantDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val picker by lazy { AndroidDocumentPicker(context, SafTestLogger, SystemMonotonicClock) }
    private val transfer by lazy { DocumentTransfer(SafTestLogger, SystemMonotonicClock) }

    @get:Rule val testTimeout: Timeout = Timeout.builder().withTimeout(55, TimeUnit.SECONDS)
        .withLookingForStuckThread(true).build()

    @Before fun traceTestEntry() = safRunnerStage("cross_uid.test", "entered")
    @After fun traceTestExit() = safRunnerStage("cross_uid.test", "body_exited")

    @Test fun noGrantDeniesBothPfdModesAndPickerAndUidsAreDifferent() = withFixture { host ->
        val result = host.fixture("probe")
        assertSeparateUid(result, context)
        val uri = requireNotNull(result.data?.data)
        assertEquals(S207.AUTHORITY, uri.authority)
        safRunnerStage("ungranted_read", "checking")
        assertDenied(context, uri, S207.READ)
        safRunnerStage("ungranted_write", "checking")
        assertDenied(context, uri, S207.WRITE)
        safRunnerStage("ungranted_picker", "checking")
        assertEquals(AndroidDocumentSelection.Rejected(DocumentSelectionFailure.ACCESS_DENIED),
            picker.acceptOpen(result.resultCode, result.data, true))
        safRunnerStage("ungranted_persistence", "checking")
        assertFalse(context.contentResolver.persistedUriPermissions.any { it.uri == uri })
        safRunnerStage("no_grant", "assertions_complete")
    }

    @Test fun crossUidReadGrantImportsPersistsRestoresThenProviderRevokes() = withTestStorage(context) { storage ->
        val port = storage.documentTransfers
        val result = persistFromFixture("read-persist") { selected ->
            val uri = requireNotNull(selected.data?.data)
            assertGranted(context, uri, S207.READ)
            assertDenied(context, uri, S207.WRITE)
            assertTrue((port.acceptOpen(selected.resultCode, selected.data, true) as AndroidDocumentSelection.Opened).persisted)
            selected
        }
        val uri = requireNotNull(result.data?.data)
        // The grant-issuing Activity owner is gone. Only the persisted grant can allow this read.
        assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && !it.isWritePermission })
        val restored = port.restoreRead(uri.toString()) as AndroidDocumentSelection.Opened
        val expected = S207.pattern()
        val digest = S207.digest(expected)
        val progress = mutableListOf<DocumentProgress>()
        val imported = runBlocking { withContext(Dispatchers.IO) {
            port.importAttachment(restored.source, StreamAttachmentRequest(S207.SOURCE, S207.MIME, expected.size.toLong()),
                DocumentCancellation(), DocumentProgressObserver { progress.add(it) })
        } } as DocumentImportResult.Stored
        assertTrue(progress.any { it.phase == DocumentPhase.COPY && it.bytes == expected.size.toLong() })
        assertEquals(digest, imported.digest)
        assertEquals(digest.sha256, imported.resource.sha256)
        runBlocking { assertArrayEquals(expected, storage.attachments.get(imported.resource.id)?.bytes) }
        withFixture(resetFirst = false) { host ->
            assertNoOutgoingPersistedGrant(host.fixture("revoke"))
            awaitReadGrantExpiry(context, uri)
            assertFalse(context.contentResolver.persistedUriPermissions.any { it.uri == uri })
            assertEquals(AndroidDocumentSelection.Rejected(DocumentSelectionFailure.ACCESS_DENIED), port.restoreRead(uri.toString()))
            expectSecurity { restored.source.open(DocumentCancellation()).close() }
            val denied = runBlocking { withContext(Dispatchers.IO) {
                port.importAttachment(restored.source, StreamAttachmentRequest(S207.SOURCE, S207.MIME, expected.size.toLong()),
                    DocumentCancellation())
            } }
            assertEquals(DocumentImportResult.Failed(DocumentPhase.OPEN_SOURCE, DocumentFailure.ACCESS_DENIED), denied)
        }
    }

    @Test fun transientGrantCannotBePersistedAndExpiresWithActivityOwner() {
        val host = FixtureHost()
        val uri: Uri
        try {
            host.fixture("reset")
            val result = host.fixture("read")
            assertSeparateUid(result, context)
            uri = requireNotNull(result.data?.data)
            val selected = picker.acceptOpen(result.resultCode, result.data, true) as AndroidDocumentSelection.Opened
            assertFalse(selected.persisted)
            selected.source.open(DocumentCancellation()).use { assertTrue(it.read() >= 0) }
            expectSecurity { context.contentResolver.takePersistableUriPermission(uri, S207.READ) }
            assertFalse(context.contentResolver.persistedUriPermissions.any { it.uri == uri })
        } finally {
            host.close()
        }
        awaitReadGrantExpiry(context, uri)
        assertDenied(context, uri, S207.READ)
        assertEquals(AndroidDocumentSelection.Rejected(DocumentSelectionFailure.ACCESS_DENIED), picker.restoreRead(uri.toString()))
    }

    @Test fun crossUidSyntheticNewTargetCanWriteReadBackAndReleasePersistedGrant() = withFixture { host ->
        withTestStorage(context) { storage ->
            val port = storage.documentTransfers
            val sourceResult = host.fixture("read-persist")
            assertSeparateUid(sourceResult, context)
            val source = port.acceptOpen(sourceResult.resultCode, sourceResult.data, true) as AndroidDocumentSelection.Opened
            val bytes = S207.pattern()
            val imported = runBlocking { withContext(Dispatchers.IO) {
                port.importAttachment(source.source, StreamAttachmentRequest(S207.SOURCE, S207.MIME, bytes.size.toLong()),
                    DocumentCancellation())
            } } as DocumentImportResult.Stored
            assertEquals(S207.digest(bytes), imported.digest)
            val result = host.fixture("new-rw-persist")
            assertSeparateUid(result, context)
            val uri = requireNotNull(result.data?.data)
            assertGranted(context, uri, S207.READ or S207.WRITE)
            // Authentic system-granted result from the test Activity, NOT ACTION_CREATE_DOCUMENT.
            val request = port.createRequest(S207.MIME, S207.EXPORT)
            val selected = request.acceptResult(result.resultCode, result.data, true) as AndroidDocumentSelection.Created
            assertTrue(selected.persisted)
            assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission })
            val progress = mutableListOf<DocumentProgress>()
            assertEquals(DocumentExportResult.Verified(S207.digest(bytes)), runBlocking { withContext(Dispatchers.IO) {
                port.exportAttachment(imported.resource.id, selected.destination, DocumentCancellation(),
                    DocumentProgressObserver { progress.add(it) })
            } })
            assertTrue(progress.any { it.phase == DocumentPhase.COPY && it.bytes == bytes.size.toLong() })
            assertTrue(progress.any { it.phase == DocumentPhase.VERIFY && it.bytes == bytes.size.toLong() })
            context.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(bytes, it.readBytes()) }
            context.contentResolver.releasePersistableUriPermission(uri, S207.READ or S207.WRITE)
            assertFalse(context.contentResolver.persistedUriPermissions.any { it.uri == uri })
            // Releasing persistence does not remove the still-live Activity-owned transient grant.
            assertGranted(context, uri, S207.READ or S207.WRITE)
        }
    }

    @Test fun writeOnlyGrantRejectsSyntheticSelectionAndCleansNewTarget() = withFixture { host ->
        val result = host.fixture("new-w")
        val uri = requireNotNull(result.data?.data)
        assertGranted(context, uri, S207.WRITE)
        assertDenied(context, uri, S207.READ)
        val selected = picker.createRequest(S207.MIME, S207.EXPORT).acceptResult(result.resultCode, result.data, false)
        assertEquals(AndroidDocumentSelection.Rejected(DocumentSelectionFailure.ACCESS_DENIED, DocumentCleanup.DELETE_REPORTED), selected)
        assertFalse(host.fixture("status").data!!.getBooleanExtra("s207.exportExists", true))
    }

    @Test fun cancellationWithWriteGrantDeletesOnlySyntheticNewTarget() = withFixture { host ->
        val result = host.fixture("new-rw-persist")
        val selected = picker.createRequest(S207.MIME, S207.EXPORT)
            .acceptResult(result.resultCode, result.data, false) as AndroidDocumentSelection.Created
        val token = DocumentCancellation()
        val actual = runBlocking {
            transfer.export(S207.exportSource(S207.pattern()), selected.destination, token, DocumentProgressObserver {
                if (it.phase == DocumentPhase.COPY && it.bytes > 0) token.cancel()
            })
        }
        assertEquals(DocumentExportResult.Cancelled(DocumentPhase.COPY, DocumentCleanup.DELETE_REPORTED), actual)
        val status = host.fixture("status").data!!
        assertFalse(status.getBooleanExtra("s207.exportExists", true))
        assertTrue(status.getBooleanExtra("s207.sourceExists", false))
    }

    @Test fun revokedCreateGrantReportsPossibleLeftoverWithoutDeleting() {
        val request = picker.createRequest(S207.MIME, S207.EXPORT)
        val rw = S207.READ or S207.WRITE
        val result = persistFromFixture("new-rw-persist") { selected ->
            val data = requireNotNull(selected.data)
            assertEquals(rw, data.flags and rw)
            val uri = requireNotNull(data.data)
            assertGranted(context, uri, rw)
            context.contentResolver.takePersistableUriPermission(uri, rw)
            selected
        }
        val data = requireNotNull(result.data)
        val uri = requireNotNull(data.data)
        assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission })
        withFixture(resetFirst = false) { host ->
            assertNoOutgoingPersistedGrant(host.fixture("revoke-export"))
            awaitReadGrantExpiry(context, uri)
            assertDenied(context, uri, S207.WRITE)
            assertTrue("fixture must still be a visible DocumentsProvider", DocumentsContract.isDocumentUri(context, uri))
            // Rejection may be observed at preflight or at I/O on a vendor with stale advisory
            // checks. Neither path may touch or pretend to have deleted the synthetic target.
            val rejected = request.acceptResult(result.resultCode, data, false)
            val bytes = S207.pattern()
            when (rejected) {
                is AndroidDocumentSelection.Rejected -> assertEquals(
                    AndroidDocumentSelection.Rejected(DocumentSelectionFailure.ACCESS_DENIED, DocumentCleanup.MAY_REMAIN), rejected,
                )
                is AndroidDocumentSelection.Created -> assertEquals(
                    DocumentExportResult.Failed(DocumentPhase.OPEN_DESTINATION, DocumentFailure.ACCESS_DENIED, DocumentCleanup.MAY_REMAIN),
                    runBlocking { transfer.export(S207.exportSource(bytes), rejected.destination, DocumentCancellation()) },
                )
                else -> fail("expected revoked export to be denied")
            }
            val status = host.fixture("status").data!!
            assertTrue(status.getBooleanExtra("s207.exportExists", false))
            assertEquals(0L, status.getLongExtra("s207.exportSize", -1L))
        }
    }

    @Test fun providerRevocationDuringExportReportsPossiblePartialWithoutGrant() {
        val (uri, selected) = persistFromFixture("new-rw-persist") { grant ->
            val selected = picker.createRequest(S207.MIME, S207.EXPORT)
                .acceptResult(grant.resultCode, grant.data, true) as AndroidDocumentSelection.Created
            assertTrue(selected.persisted)
            requireNotNull(grant.data?.data) to selected
        }
        withFixture(resetFirst = false) { host ->
            val bytes = S207.pattern()
            val chunk = 8192
            val interrupted = DocumentExportSource { output, token ->
                output.write(bytes, 0, chunk)
                assertNoOutgoingPersistedGrant(host.fixture("revoke-export"))
                awaitReadGrantExpiry(context, uri)
                // Revocation is not assumed to close the kernel descriptor we already opened.
                token.cancel()
                token.check()
                S207.digest(bytes)
            }
            val actual = runBlocking { transfer.export(interrupted, selected.destination, DocumentCancellation()) }
            assertEquals(DocumentExportResult.Cancelled(DocumentPhase.COPY, DocumentCleanup.MAY_REMAIN), actual)
            assertDenied(context, uri, S207.WRITE)
            assertFalse(context.contentResolver.persistedUriPermissions.any { it.uri == uri })
            val status = host.fixture("status").data!!
            assertTrue(status.getBooleanExtra("s207.exportExists", false))
            assertEquals(chunk.toLong(), status.getLongExtra("s207.exportSize", -1L))
            assertTrue(status.getBooleanExtra("s207.sourceExists", false))
        }
    }

    private fun <T> persistFromFixture(operation: String, persist: (ActivityResult) -> T): T =
        FixtureHost().use { host ->
            host.fixture("reset")
            val result = host.fixture(operation)
            assertSeparateUid(result, context)
            persist(result)
        }

    private fun assertNoOutgoingPersistedGrant(result: ActivityResult) =
        assertEquals(0, requireNotNull(result.data).getIntExtra("s207.outgoingAfter", -1))

    private fun withFixture(resetFirst: Boolean = true, block: (FixtureHost) -> Unit) {
        safRunnerStage("fixture.host", "constructing")
        FixtureHost().use { host ->
            assertSeparateUid(host.fixture(if (resetFirst) "reset" else "probe"), context)
            try { block(host) } finally { host.fixture("reset") }
        }
    }
}

internal object S207 {
    const val PACKAGE = "com.deepstudent.services.resources.data.test"
    const val AUTHORITY = "com.deepstudent.services.resources.data.test.s207grant"
    const val FIXTURE_ACTIVITY = "com.deepstudent.services.resources.data.saf.GrantFixtureActivity"
    const val ROOT = "s207-root"
    const val ROOT_LABEL = "DSTU S207 Test Only"
    const val SOURCE = "s207-fixture-source.bin"
    const val EXPORT = "s207-fixture-export.bin"
    const val MIME = "application/octet-stream"
    const val READ = Intent.FLAG_GRANT_READ_URI_PERMISSION
    const val WRITE = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    fun pattern() = ByteArray(2 * 1024 * 1024 + 31) { ((it * 31 + 7) and 255).toByte() }
    fun digest(bytes: ByteArray) = AssetDigest(Digests.sha256Hex(bytes), bytes.size.toLong())
    fun exportSource(bytes: ByteArray) = DocumentExportSource { output, cancellation ->
        var offset = 0
        while (offset < bytes.size) {
            cancellation.check()
            val count = minOf(8192, bytes.size - offset)
            output.write(bytes, offset, count)
            offset += count
        }
        digest(bytes)
    }
}

internal object SafTestLogger : Logger {
    override fun log(record: LogRecord) {
        Log.i("DstuSafGrant", "event=${record.event} correlationId=${record.correlationId?.value} fields=${record.fields}")
    }
}

internal fun assertSeparateUid(result: ActivityResult, context: Context) {
    @Suppress("DEPRECATION")
    val token = result.data?.getParcelableExtra<PendingIntent>("s207.uidProof")
    assertNotNull("fixture APK must be installed; source must return OS-authenticated UID proof", token)
    assertEquals(S207.PACKAGE, token!!.creatorPackage)
    assertEquals(S207.PACKAGE, result.data!!.getStringExtra("s207.providerPackage"))
    assertEquals(token.creatorUid, result.data!!.getIntExtra("s207.providerUid", -1))
    assertNotEquals("fixture provider and App must not share UID", Process.myUid(), token.creatorUid)
    assertEquals(context.applicationInfo.uid, Process.myUid())
    Log.i("DstuSafGrant", "owner=app.s207 phase=uid state=verified appUid=${Process.myUid()} fixtureUid=${token.creatorUid}")
}

internal fun assertGranted(context: Context, uri: Uri, flags: Int) {
    assertEquals(PackageManager.PERMISSION_GRANTED, context.checkUriPermission(uri, Process.myPid(), Process.myUid(), flags))
}

internal fun assertDenied(context: Context, uri: Uri, flag: Int) {
    // The provider/OS opening the descriptor is authoritative. A vendor grant preflight
    // may still report GRANTED after Android actually removed the grant.
    expectSecurity { context.contentResolver.openFileDescriptor(uri, if (flag == S207.READ) "r" else "w")!!.close() }
}

internal fun expectSecurity(block: () -> Unit) {
    try { block(); fail("expected system SecurityException") } catch (_: SecurityException) { }
}

/** Isolated on-device private store; never modify the App's active user store. */
internal fun <T> withTestStorage(base: Context, block: (MainStorageResources) -> T): T {
    val root = File(base.cacheDir, "s207-grant-" + UUID.randomUUID()).apply { check(mkdirs()) }
    val isolated = object : ContextWrapper(base) { override fun getFilesDir(): File = root }
    try { return MainStorageResources.open(isolated).use(block) }
    finally { check(root.deleteRecursively()) { "failed to remove synthetic test store" } }
}
