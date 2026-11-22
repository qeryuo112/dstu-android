package com.deepstudent.app.workspace

import com.deepstudent.features.workspace.api.PanelHost
import com.deepstudent.features.workspace.api.PanelKey
import com.deepstudent.features.workspace.api.PanelSaveContext
import com.deepstudent.features.workspace.api.PanelSaveResult
import com.deepstudent.features.workspace.api.ResourceKey

internal class ReadOnlyPanelAdapter(
    override val key: PanelKey,
    override val title: String,
    private val resourceType: String,
) : PanelHost {
    override fun accepts(resource: ResourceKey?): Boolean = resource == null || resource.type == resourceType
    override suspend fun save(context: PanelSaveContext): PanelSaveResult = PanelSaveResult.NoChanges
}
