package com.deepstudent.services.backup.backend

import com.deepstudent.services.backup.api.RestoreScope
import com.deepstudent.services.backup.api.RestoreTrust

/** Desktop e6b2657 registry. Desktop restoreTarget is evidence, never an Android routing instruction. */
internal data class DomainSpec(
    val id: String,
    val archiveRoot: String,
    val desktopTarget: String,
    val required: Boolean = false,
    val executable: Boolean = false,
    val encrypted: Boolean = false,
    val scope: RestoreScope = RestoreScope.ACTIVE_DATA_SPACE,
    val trust: RestoreTrust = RestoreTrust.DATA,
) {
    val optional: Boolean get() = !required
    fun owns(path: String): Boolean = atOrBelow(path, archiveRoot) ||
        (id.startsWith("asset-root:") && atOrBelow(path, id.substringAfter(':'))) ||
        (id == "agents" && atOrBelow(path, "workspaces/agents"))
}

internal fun atOrBelow(path: String, root: String): Boolean = path == root || path.startsWith("$root/")

internal object FrozenDomains {
    val databaseIds = listOf("vfs", "llm_usage", "chat_v2", "mistakes")
    val assetIds = listOf(
        "images", "notes_assets", "documents", "vfs_blobs", "subjects", "workspaces",
        "audio", "videos", "textbooks", "pdf_ocr_sessions",
    )
    val all: List<DomainSpec> = buildList {
        databaseIds.forEach { id ->
            add(DomainSpec("database:$id", "$id.db", if (id == "vfs") "databases/vfs.db" else "$id.db", required = true))
        }
        add(DomainSpec("workspaces-root", "workspaces", "workspaces"))
        assetIds.forEach { add(DomainSpec("asset-root:$it", "assets/$it", it)) }
        add(DomainSpec("crypto", "crypto", ".", encrypted = true, scope = RestoreScope.APPLICATION_DATA, trust = RestoreTrust.EXPLICIT))
        add(DomainSpec("audit", "databases/audit.db", "databases/audit.db", scope = RestoreScope.APPLICATION_DATA, trust = RestoreTrust.EXPLICIT))
        add(DomainSpec("webview-settings", "persistent/webview_settings.json", "webview_settings.json"))
        add(DomainSpec("custom-grading-modes", "persistent/custom_grading_modes.json", "custom_grading_modes.json"))
        add(DomainSpec("agents", "assets/workspaces/agents", "workspaces/agents", executable = true, trust = RestoreTrust.UNTRUSTED_EXECUTABLE))
        add(DomainSpec("user-skills", "persistent/user_skills", "~/.deep-student/skills", executable = true, encrypted = true, scope = RestoreScope.USER_HOME, trust = RestoreTrust.UNTRUSTED_EXECUTABLE))
    }
    val byId = all.associateBy(DomainSpec::id)
    // Includes the shared S2.04 repair migration; the S0 frozen lock remains unchanged.
    val latestSchema = mapOf("vfs" to 20261120L, "chat_v2" to 20261111L, "mistakes" to 20261006L, "llm_usage" to 20260826L)
    fun requiresTrust(path: String): Boolean = all.any { it.trust == RestoreTrust.UNTRUSTED_EXECUTABLE && it.owns(path) }
}
