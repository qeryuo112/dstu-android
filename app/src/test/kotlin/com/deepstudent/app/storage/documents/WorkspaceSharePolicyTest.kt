package com.deepstudent.app.storage.documents

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

internal class WorkspaceSharePolicyTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun revocationIsDurableAndDocumentScoped() {
        val directory = File(folder.root, "private")
        val revoked = WorkspaceDocumentKey(DocumentRoot.WORKSPACE, "att_ws_0123456789abcdef0123456789abcdef")
        val other = WorkspaceDocumentKey(DocumentRoot.WORKSPACE, "att_ws_0123456789abcdef0123456789abc000")
        WorkspaceSharePolicy(directory).apply {
            assertFalse(hidden(revoked))
            requireAccess(revoked, false)
            deny(revoked)
            deny(revoked)
        }
        WorkspaceSharePolicy(directory).apply {
            assertTrue(hidden(revoked))
            assertFalse(hidden(other))
            assertThrows(SecurityException::class.java) { requireAccess(revoked, false) }
            requireAccess(revoked, true)
            requireAccess(other, false)
        }
    }
}
