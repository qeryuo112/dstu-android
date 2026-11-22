package com.deepstudent.features.workspace.data

import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException
import com.deepstudent.features.workspace.api.DraftStore
import com.deepstudent.features.workspace.api.LayoutSnapshotStore
import com.deepstudent.features.workspace.api.LayoutState
import com.deepstudent.features.workspace.api.PanelDraft
import com.deepstudent.features.workspace.api.PanelId
import com.deepstudent.features.workspace.backend.domain.LayoutSnapshotCodec
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Local workspace snapshot and draft storage. Each file replacement is atomic;
 * the layout and drafts are independent atomic documents.
 */
class AtomicWorkspaceStore(
    root: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DraftStore, LayoutSnapshotStore {
    private val layoutFile = File(root, LAYOUT_FILE)
    private val draftsDirectory = File(root, DRAFTS_DIRECTORY)
    private val mutex = Mutex()
    private val codec = LayoutSnapshotCodec()

    override suspend fun load(panelId: PanelId): PanelDraft? = withContext(io) {
        mutex.withLock {
            val file = draftFile(panelId)
            if (!file.isFile) return@withLock null
            if (file.length() > MAX_DRAFT_FILE_BYTES) corruptedDraft()
            val draft = DraftFileCodec.decode(file.readBytes())
            if (draft.panelId != panelId) corruptedDraft()
            draft
        }
    }

    override suspend fun save(draft: PanelDraft): PanelDraft = withContext(io) {
        mutex.withLock {
            draftsDirectory.mkdirs()
            AtomicFileWriter.write(draftFile(draft.panelId), DraftFileCodec.encode(draft))
            draft
        }
    }

    override suspend fun delete(panelId: PanelId): Boolean = withContext(io) {
        mutex.withLock {
            val file = draftFile(panelId)
            if (!file.exists()) return@withLock false
            if (!file.delete()) {
                throw DstuException(
                    DstuError.unavailable(
                        "WORKSPACE_DRAFT_DELETE_FAILED",
                        "workspace draft could not be deleted",
                    ),
                )
            }
            true
        }
    }

    override suspend fun load(): LayoutState? = withContext(io) {
        mutex.withLock {
            if (!layoutFile.isFile) return@withLock null
            if (layoutFile.length() > MAX_LAYOUT_FILE_BYTES) corruptedLayout()
            codec.decode(layoutFile.readText(Charsets.UTF_8))
        }
    }

    override suspend fun save(layout: LayoutState) = withContext(io) {
        mutex.withLock {
            val parent = layoutFile.parentFile ?: error("workspace layout directory is unavailable")
            parent.mkdirs()
            AtomicFileWriter.write(layoutFile, codec.encode(layout).toByteArray(Charsets.UTF_8))
        }
    }

    override suspend fun clear(): Unit = withContext(io) {
        mutex.withLock {
            deleteRequired(layoutFile)
            draftsDirectory.listFiles()?.forEach(::deleteRequired)
        }
        Unit
    }

    private fun draftFile(panelId: PanelId): File =
        File(draftsDirectory, "${Digests.sha256HexUtf8(panelId.value)}.draft")

    private fun corruptedDraft(): Nothing = throw DraftFileCodec.corrupt()

    private fun corruptedLayout(): Nothing = throw DstuException(
        DstuError.validation(
            "WORKSPACE_SNAPSHOT_INVALID",
            "invalid workspace layout snapshot",
        ),
    )

    private fun deleteRequired(file: File) {
        if (file.exists() && !file.delete()) {
            throw DstuException(
                DstuError.unavailable(
                    "WORKSPACE_STORE_CLEAR_FAILED",
                    "workspace store could not be cleared",
                ),
            )
        }
    }

    companion object {
        private const val LAYOUT_FILE = "layout-v1.json"
        private const val DRAFTS_DIRECTORY = "drafts"
        private const val MAX_LAYOUT_FILE_BYTES = 4L * 1024 * 1024
        private const val MAX_DRAFT_FILE_BYTES = PanelDraft.MAX_BYTES * 2L
    }
}
