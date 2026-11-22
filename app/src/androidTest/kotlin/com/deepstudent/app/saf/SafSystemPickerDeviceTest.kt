package com.deepstudent.app.saf

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.storage.MainStorageResources
import com.deepstudent.services.resources.api.DocumentCancellation
import com.deepstudent.services.resources.api.DocumentExportResult
import com.deepstudent.services.resources.api.DocumentImportResult
import com.deepstudent.services.resources.api.StreamAttachmentRequest
import com.deepstudent.services.resources.data.saf.AndroidDocumentSelection
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
 * Actual ACTION_OPEN_DOCUMENT / ACTION_CREATE_DOCUMENT grants from system DocumentsUI.
 * Only the test pins that picker. Accessibility clicks are restricted to the synthetic provider
 * root, its one named fixture and the Save control. Both paths use an isolated real App store.
 */
@RunWith(AndroidJUnit4::class)
class SafSystemPickerDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val root get() = DocumentsContract.buildDocumentUri(S207.AUTHORITY, S207.ROOT)

    @get:Rule val testTimeout: Timeout = Timeout.builder().withTimeout(100, TimeUnit.SECONDS)
        .withLookingForStuckThread(true).build()

    @Before fun traceTestEntry() = safRunnerStage("system_picker.test", "entered")
    @After fun traceTestExit() = safRunnerStage("system_picker.test", "body_exited")

    @Test fun systemOpenDocumentReturnsRealGrantAndImportsSyntheticFile() = withFixture { host, storage ->
        val port = storage.documentTransfers
        val intent = port.openIntent(S207.MIME).putExtra(DocumentsContract.EXTRA_INITIAL_URI, root)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertNull(intent.component)
        assertNull(intent.`package`) // production request remains generic
        val result = host.launch(systemRoute(intent), pickerInput { openSyntheticSource() })
        val data = requireNotNull(result.data)
        assertEquals(S207.AUTHORITY, data.data?.authority)
        assertEquals(S207.SOURCE, DocumentsContract.getDocumentId(requireNotNull(data.data)))
        val selected = port.acceptOpen(result.resultCode, data, true) as AndroidDocumentSelection.Opened
        assertTrue("system picker did not provide a persistable read grant", selected.persisted)
        val bytes = S207.pattern()
        val imported = runBlocking { withContext(Dispatchers.IO) {
            port.importAttachment(selected.source, StreamAttachmentRequest(S207.SOURCE, S207.MIME, bytes.size.toLong()),
                DocumentCancellation())
        } } as DocumentImportResult.Stored
        assertEquals(S207.digest(bytes), imported.digest)
        runBlocking { assertArrayEquals(bytes, storage.attachments.get(imported.resource.id)?.bytes) }
        assertTrue(port.releasePersistedRead(selected.source.reference()))
    }

    @Test fun systemCreateDocumentReturnsRealGrantAndExportsSyntheticFile() = withFixture { host, storage ->
        val port = storage.documentTransfers
        // Populate the real store from the controlled cross-UID source before launching DocumentsUI.
        val grant = host.fixture("read-persist")
        val source = port.acceptOpen(grant.resultCode, grant.data, true) as AndroidDocumentSelection.Opened
        val bytes = S207.pattern()
        val imported = runBlocking { withContext(Dispatchers.IO) {
            port.importAttachment(source.source, StreamAttachmentRequest(S207.SOURCE, S207.MIME, bytes.size.toLong()),
                DocumentCancellation())
        } } as DocumentImportResult.Stored
        assertEquals(S207.digest(bytes), imported.digest)
        val request = port.createRequest(S207.MIME, S207.EXPORT)
        request.intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, root)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.action)
        assertNull(request.intent.component)
        assertNull(request.intent.`package`) // production request remains generic
        val result = host.launch(systemRoute(request.intent), pickerInput { createSyntheticTarget() })
        val data = requireNotNull(result.data)
        val uri = requireNotNull(data.data)
        assertEquals(S207.AUTHORITY, uri.authority)
        assertEquals(S207.EXPORT, DocumentsContract.getDocumentId(uri))
        val selected = request.acceptResult(result.resultCode, data, true) as AndroidDocumentSelection.Created
        assertTrue("system picker did not provide persistable read/write grants", selected.persisted)
        assertEquals(DocumentExportResult.Verified(S207.digest(bytes)), runBlocking { withContext(Dispatchers.IO) {
            port.exportAttachment(imported.resource.id, selected.destination, DocumentCancellation())
        } })
        context.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(bytes, it.readBytes()) }
        context.contentResolver.releasePersistableUriPermission(uri, S207.READ or S207.WRITE)
    }

    // When the device freezes the caller while DocumentsUI is foreground, the host operator
    // selects the synthetic document externally. The result/grant/PFD/hash assertions are unchanged.
    private fun pickerInput(action: SystemPickerUi.() -> Unit): (() -> Unit)? =
        if (InstrumentationRegistry.getArguments().getString("s207ManualPicker") == "true") null
        else ({ SystemPickerUi().action() })

    /** Package-only OPEN is redirected to the OEM file manager on V2232A. */
    private fun systemRoute(intent: Intent): Intent {
        val routed = Intent(intent).setPackage(DOCUMENTS_UI)
        @Suppress("DEPRECATION")
        val picker = context.packageManager.queryIntentActivities(routed, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo }.singleOrNull {
                it.packageName == DOCUMENTS_UI && it.exported &&
                    it.applicationInfo.flags and SYSTEM_APP_FLAGS != 0
            }
        checkNotNull(picker) { "Standard system DocumentsUI did not expose a unique picker" }
        return routed.setComponent(ComponentName(picker.packageName, picker.name))
    }

    private fun withFixture(block: (FixtureHost, MainStorageResources) -> Unit) {
        safRunnerStage("fixture.host", "constructing")
        FixtureHost().use { host ->
            host.fixture("reset")
            assertSeparateUid(host.fixture("probe"), context)
            try { withTestStorage(context) { storage -> block(host, storage) } }
            finally { host.fixture("reset") }
        }
    }

}

private class SystemPickerUi {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation

    fun openSyntheticSource() {
        enterSyntheticRoot()
        waitFor("synthetic source") { root -> isSyntheticDirectory(root) && clickExact(root, S207.SOURCE) }
    }

    fun createSyntheticTarget() {
        enterSyntheticRoot()
        // EXTRA_TITLE must still be our exact synthetic filename before pressing Save.
        waitFor("synthetic target and Save") { root ->
            if (!isSyntheticDirectory(root) || !containsExact(root, S207.EXPORT)) return@waitFor false
            clickSave(root)
        }
    }

    private fun enterSyntheticRoot() {
        // EXTRA_INITIAL_URI is advisory. Only trust the currently displayed directory when its
        // toolbar says our fixture root and our exact source is visible. Otherwise select our root
        // from the picker navigation; never click a document in an unconfirmed directory.
        var drawerOpened = false
        var rootSelected = false
        waitFor("synthetic provider root") { root ->
            if (isSyntheticDirectory(root)) return@waitFor true
            if (!rootSelected) rootSelected = clickNavigationRoot(root)
            if (!rootSelected && !drawerOpened) drawerOpened = openDrawer(root)
            false
        }
        waitFor("synthetic directory") { root -> isSyntheticDirectory(root) }
    }

    private fun isSyntheticDirectory(root: AccessibilityNodeInfo): Boolean =
        containsExact(root, S207.SOURCE) && visit(root) { node ->
            (node.text?.toString() == S207.ROOT_LABEL || node.contentDescription?.toString() == S207.ROOT_LABEL) &&
                hasToolbarAncestor(node)
        }

    private fun clickNavigationRoot(root: AccessibilityNodeInfo): Boolean = visit(root) { node ->
        (node.text?.toString() == S207.ROOT_LABEL || node.contentDescription?.toString() == S207.ROOT_LABEL) &&
            !hasToolbarAncestor(node) && clickNodeOrParent(node)
    }

    private fun hasToolbarAncestor(node: AccessibilityNodeInfo): Boolean {
        @Suppress("DEPRECATION")
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        try {
            repeat(10) {
                val item = current ?: return false
                val id = item.viewIdResourceName.orEmpty()
                val type = item.className?.toString().orEmpty()
                if (id.endsWith(":id/toolbar") || id.endsWith(":id/action_bar") || type.endsWith("Toolbar")) return true
                val parent = item.parent
                @Suppress("DEPRECATION")
                item.recycle()
                current = parent
            }
            return false
        } finally { @Suppress("DEPRECATION") current?.recycle() }
    }

    private fun waitFor(description: String, action: (AccessibilityNodeInfo) -> Boolean) {
        safRunnerStage("system_picker.ui", "searching")
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = automation.rootInActiveWindow
            if (root != null) {
                try {
                    val pkg = root.packageName?.toString()
                    if (pkg != null && isSystemPicker(pkg) && action(root)) {
                        Log.i("DstuSafGrant", "owner=app.s207 phase=system_picker state=observed package=$pkg step=$description")
                        safRunnerStage("system_picker.ui", "observed")
                        return
                    }
                } finally { @Suppress("DEPRECATION") root.recycle() }
            }
            Thread.sleep(200)
        }
        safRunnerStage("system_picker.ui", "timeout")
        throw AssertionError("System picker did not expose $description; no user directory/file was selected")
    }

    private fun isSystemPicker(pkg: String): Boolean {
        if (pkg != DOCUMENTS_UI) return false
        return try {
            @Suppress("DEPRECATION")
            val flags = context.packageManager.getApplicationInfo(pkg, 0).flags
            flags and SYSTEM_APP_FLAGS != 0
        } catch (_: Exception) { false }
    }

    private fun containsExact(root: AccessibilityNodeInfo, text: String): Boolean =
        visit(root) { node -> node.text?.toString() == text || node.contentDescription?.toString() == text }

    private fun clickExact(root: AccessibilityNodeInfo, text: String): Boolean =
        visit(root) { node ->
            (node.text?.toString() == text || node.contentDescription?.toString() == text) && clickNodeOrParent(node)
        }

    private fun clickSave(root: AccessibilityNodeInfo): Boolean = visit(root) { node ->
        val text = node.text?.toString()?.trim()?.lowercase()
        text in setOf("save", "保存", "儲存", "储存") &&
            node.className?.toString()?.contains("Button") == true && clickNodeOrParent(node)
    }

    private fun openDrawer(root: AccessibilityNodeInfo): Boolean = visit(root) { node ->
        val description = node.contentDescription?.toString()?.lowercase() ?: return@visit false
        val allowed = description in setOf("show roots", "open navigation drawer", "show navigation drawer", "显示根目录", "显示导航抽屉", "打开导航抽屉", "顯示根目錄")
        allowed && clickNodeOrParent(node)
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        @Suppress("DEPRECATION")
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        try {
            repeat(4) {
                val item = current ?: return false
                if (item.isClickable && item.isEnabled) return item.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                val next = item.parent
                @Suppress("DEPRECATION")
                item.recycle()
                current = next
            }
            return false
        } finally { @Suppress("DEPRECATION") current?.recycle() }
    }

    private fun visit(node: AccessibilityNodeInfo, action: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (action(node)) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { if (visit(child, action)) return true } finally { @Suppress("DEPRECATION") child.recycle() }
        }
        return false
    }
}

private const val DOCUMENTS_UI = "com.android.documentsui"
private const val SYSTEM_APP_FLAGS = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
