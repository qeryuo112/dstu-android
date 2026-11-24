package com.deepstudent.app.saf

import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor.AutoCloseOutputStream
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.DstuApplication
import com.deepstudent.app.storage.documents.WorkspaceDocumentInfo
import com.deepstudent.app.storage.documents.WorkspaceDocumentPrivacy
import com.deepstudent.app.storage.documents.WorkspaceSharePolicy
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.resources.api.CreateAttachment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileNotFoundException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkspaceProviderDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as DstuApplication
    private val authority get() = "${context.packageName}.documents.workspace"
    private val resolver get() = context.contentResolver
    private val root get() = DocumentsContract.buildDocumentUri(authority, "workspace")

    @Test fun systemPickerCrossUidWriteReadAndStableId(): Unit = runBlocking {
        check(context.packageName == "com.deepstudent.app.s2device") { "isolated test APK required" }
        val title = "DSTU-S228-${UUID.randomUUID().toString().take(8)}.txt"
        val client = Intent().setClassName(S207.PACKAGE, "com.deepstudent.services.resources.data.saf.WorkspacePickerClient")
        FixtureHost().use { host ->
            val result = host.launch(
                Intent(client).putExtra("case", "create").putExtra("title", title),
                if (InstrumentationRegistry.getArguments().getString("s228ManualPicker") == "true") null else ({ chooseSave(title) }),
            )
            val data = requireNotNull(result.data)
            assertTrue("fixture failure: ${data.getStringExtra("error")}", data.getBooleanExtra("passed", false))
            assertNotEquals(Process.myUid(), data.getIntExtra("uid", -1))
            val uri = Uri.parse(requireNotNull(data.getStringExtra("document")))
            val id = DocumentsContract.getDocumentId(uri).substringAfter(':')
            try {
                val storage = app.storage()
                val prefix = "S228 fixture $title".toByteArray()
                val bytes = ByteArray(512 * 1024) { index -> if (index < prefix.size) prefix[index] else (index % 251).toByte() }
                assertArrayEquals(bytes, storage.attachments.get(id)?.bytes)
                assertEquals(listOf("INSERT"), changes(id))
                val row = requireNotNull(storage.workspaceFiles.stat(id))
                val privacy = WorkspaceDocumentPrivacy(context, storage.workspaceFiles)
                privacy.makePrivate(row)
                assertTrue(WorkspaceSharePolicy.open(context).hidden(WorkspaceDocumentInfo.from(row).key))
                verifyRevokedHandles(host, client, uri, data, bytes)
                assertArrayEquals(bytes, storage.attachments.get(id)?.bytes)
                assertEquals(listOf("INSERT"), changes(id))
                verifyPrivacyOverridesFreshGrant(host, client, uri, data)
                val renamed = DocumentsContract.renameDocument(resolver, uri, "$title-renamed")
                assertEquals(uri, renamed)
                assertEquals(listOf("INSERT", "UPDATE"), changes(id))
                val next = "edited by provider $id".toByteArray()
                write(uri, next)
                assertArrayEquals(next, storage.attachments.get(id)?.bytes)
                assertEquals(listOf("INSERT", "UPDATE", "UPDATE"), changes(id))
                println("S228_PICKER external_uid=true grant=true exact_capture=true stable=true backed_revocation=true")
            } finally {
                DocumentsContract.deleteDocument(resolver, uri)
            }
        }
    }

    @Test fun privateGovernancePathsCannotResolveAndExistingAttachmentsAreReadOnly(): Unit = runBlocking {
        val invalid = listOf("workspace:../databases/vfs.db", "workspace:runtime.db", "attachments:runtime.db", "attachments:__change_log")
        for (id in invalid) {
            val uri = DocumentsContract.buildDocumentUri(authority, id)
            assertThrows(FileNotFoundException::class.java) { resolver.openFileDescriptor(uri, "r")?.close() }
        }
        val storage = app.storage()
        val bytes = "S228 attachment ${UUID.randomUUID()}".toByteArray()
        val row = storage.attachments.create(CreateAttachment("fixture.txt", "text/plain", bytes))
        val uri = DocumentsContract.buildDocumentUri(authority, "attachments:${row.id}")
        try {
            assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
            assertThrows(FileNotFoundException::class.java) { resolver.openFileDescriptor(uri, "w")?.close() }
            val otherTree = DocumentsContract.buildTreeDocumentUri(authority, "workspace")
            val crossed = DocumentsContract.buildDocumentUriUsingTree(otherTree, "attachments:${row.id}")
            assertThrows(SecurityException::class.java) { resolver.openFileDescriptor(crossed, "r")?.close() }
            assertEquals(listOf("INSERT"), changes(row.id))
        } finally {
            storage.attachments.delete(row.id)
        }
    }

    @Test fun competingWriterCannotReplaceNewerCommittedVersion(): Unit = runBlocking {
        val uri = assertCreated("DSTU-S228-${UUID.randomUUID().toString().take(8)}.txt")
        val first = UUID.randomUUID().toString().toByteArray()
        val second = UUID.randomUUID().toString().toByteArray()
        val id = DocumentsContract.getDocumentId(uri).substringAfter(':')
        try {
            AutoCloseOutputStream(resolver.openFileDescriptor(uri, "w")!!).use { stale ->
                stale.write(first)
                write(uri, second)
                assertThrows(java.io.SyncFailedException::class.java) { stale.fd.sync() }
            }
            assertArrayEquals(second, resolver.openInputStream(uri)!!.use { it.readBytes() })
            assertEquals(listOf("INSERT"), changes(id))
        } finally {
            DocumentsContract.deleteDocument(resolver, uri)
        }
    }

    private fun verifyRevokedHandles(host: FixtureHost, client: Intent, uri: Uri, data: Intent, bytes: ByteArray) {
        val lease = requireNotNull(data.getStringExtra("lease"))
        val results = probe(host, client, uri, Intent().putExtra("case", "probe").putExtra("lease", lease))
        assertEquals(data.getIntExtra("uid", -1), results.getIntExtra("uid", -1))
        verifyNewHandlesDenied(results)
        val read = results.getStringExtra("existingRead")
        val write = results.getStringExtra("existingWritten")
        val sync = results.getStringExtra("existingSync")
        println(
            "S228_PRIVACY held_read=$read held_write=$write held_fsync=$sync platform_grant_present=${results.getBooleanExtra(
                "readGranted",
                false,
            )}",
        )
        assertEquals("revoked staged write must not fsync", "denied", sync)
        assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
    }

    private fun verifyPrivacyOverridesFreshGrant(host: FixtureHost, client: Intent, uri: Uri, created: Intent) {
        val input = Intent().setData(uri).putExtra("case", "probeFreshGrant")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val reply = probe(host, client, uri, input)
        assertEquals(created.getIntExtra("uid", -1), reply.getIntExtra("uid", -1))
        assertTrue("fixture did not acquire a current Android read grant", reply.getBooleanExtra("readGranted", false))
        assertTrue("fixture did not acquire a current Android write grant", reply.getBooleanExtra("writeGranted", false))
        verifyNewHandlesDenied(reply)
        println("S228_PRIVACY valid_platform_grant=true provider_read_write=denied file_preserved=true")
    }

    private fun probe(host: FixtureHost, client: Intent, uri: Uri, input: Intent): Intent {
        val intent = Intent(input).setComponent(client.component).putExtra("document", uri.toString())
        val result = requireNotNull(host.launch(intent, null).data)
        assertTrue("probe failed: ${result.getStringExtra("error")}", result.getBooleanExtra("passed", false))
        return result
    }

    private fun verifyNewHandlesDenied(results: Intent) {
        assertTrue("external UID could still open a read descriptor", results.getBooleanExtra("readDenied", false))
        assertTrue("external UID could still open a write descriptor", results.getBooleanExtra("writeDenied", false))
    }

    private fun assertCreated(name: String): Uri {
        val result = DocumentsContract.createDocument(resolver, root, "text/plain", name)
        assertNotNull(result)
        return requireNotNull(result)
    }

    private fun write(uri: Uri, bytes: ByteArray) = AutoCloseOutputStream(resolver.openFileDescriptor(uri, "w")!!).use {
        it.write(bytes)
        it.fd.sync()
    }

    private suspend fun changes(id: String): List<String> = app.storage().databaseForSync().query(
        "SELECT operation FROM __change_log WHERE table_name='files' AND record_id=? ORDER BY id",
        listOf(SqlValue.Text(id)),
    ) { it.text(0) }

    private fun chooseSave(title: String) {
        val deadline = SystemClock.elapsedRealtime() + 12_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val window = instrumentation.uiAutomation.rootInActiveWindow
            if (window?.packageName?.toString() == "com.android.documentsui" && saveInConfirmedRoot(window, title)) return
            Thread.sleep(200)
        }
        throw AssertionError("DocumentsUI did not show the synthetic workspace destination")
    }

    private fun saveInConfirmedRoot(window: AccessibilityNodeInfo, title: String): Boolean {
        val inWorkspace = window.findAccessibilityNodeInfosByText("Deep Student 工作区").any { node ->
            node.text?.toString() == "Deep Student 工作区"
        }
        val name = title.removeSuffix(".txt")
        val hasTitle = window.findAccessibilityNodeInfosByText(name).any { it.text?.toString() in setOf(title, name) }
        if (!inWorkspace || !hasTitle) return false
        return listOf("save", "保存", "储存", "儲存").any { label ->
            window.findAccessibilityNodeInfosByText(label).any {
                it.text?.toString()?.lowercase() == label &&
                    it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
    }
}
