package com.deepstudent.features.workspace.api

import com.deepstudent.core.common.error.DstuError

sealed interface PanelSaveResult {
    data class Saved(
        val resource: ResourceKey? = null,
        val title: String? = null,
    ) : PanelSaveResult

    data object NoChanges : PanelSaveResult

    data class Failed(val error: DstuError) : PanelSaveResult
}

data class PanelSaveContext(
    val panel: PanelInstance,
    val draft: PanelDraft?,
)

data class PanelDiscardContext(
    val panel: PanelInstance,
    val draft: PanelDraft?,
)

/**
 * Domain-owned panel adapter. The app registers one host per openable panel.
 * Save and discard execute domain work; workspace only coordinates the close
 * decision and draft lifecycle.
 */
interface PanelHost {
    val key: PanelKey

    val title: String

    fun accepts(resource: ResourceKey?): Boolean = true

    suspend fun save(context: PanelSaveContext): PanelSaveResult

    suspend fun discard(context: PanelDiscardContext) = Unit

    fun onClosed(panel: PanelInstance) = Unit
}
