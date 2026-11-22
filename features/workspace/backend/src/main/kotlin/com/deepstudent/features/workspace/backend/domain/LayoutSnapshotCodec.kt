package com.deepstudent.features.workspace.backend.domain

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.json.JsonFields
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.workspace.api.LayoutState
import com.deepstudent.features.workspace.api.PanelId
import com.deepstudent.features.workspace.api.PanelInstance
import com.deepstudent.features.workspace.api.PanelKey
import com.deepstudent.features.workspace.api.ResourceKey
import com.deepstudent.features.workspace.api.WorkspaceMode

/** Versioned JSON layout snapshot. Draft bytes are intentionally absent. */
class LayoutSnapshotCodec {
    fun encode(layout: LayoutState): String = JsonValue.obj(
        "version" to JsonValue.integer(FORMAT_VERSION),
        "mode" to JsonValue.text(layout.mode.name),
        "activePanelId" to layout.activePanelId.toJson(),
        "revision" to JsonValue.integer(layout.revision),
        "panels" to JsonValue.array(layout.panels.map(::encodePanel)),
    ).canonical()

    fun decode(encoded: String): LayoutState {
        if (encoded.length > MAX_CHARS) invalid()
        return try {
            decodeObject(JsonValue.parseObject(encoded, "workspace layout"))
        } catch (failure: Exception) {
            invalid()
        }
    }

    private fun decodeObject(value: JsonValue.Object): LayoutState {
        if (JsonFields.integer(value, "version") != FORMAT_VERSION) invalid()
        val mode = mode(JsonFields.string(value, "mode"))
        val active = JsonFields.optionalString(value, "activePanelId")?.let(PanelId::parse)
        val revision = JsonFields.integer(value, "revision")
        val panels = value["panels"] as? JsonValue.Array ?: invalid()
        return LayoutState(
            mode = mode,
            panels = panels.values.map(::decodePanel),
            activePanelId = active,
            revision = revision,
        )
    }

    private fun encodePanel(panel: PanelInstance): JsonValue.Object = JsonValue.obj(
        "id" to JsonValue.text(panel.id.value),
        "key" to JsonValue.text(panel.key.value),
        "resource" to panel.resource.toJson(),
        "title" to JsonValue.text(panel.title),
        "dirty" to JsonValue.bool(panel.dirty),
        "draftRevision" to JsonValue.integer(panel.draftRevision),
        "saveUncertain" to JsonValue.bool(panel.saveUncertain),
    )

    private fun decodePanel(value: JsonValue): PanelInstance {
        val panel = value as? JsonValue.Object ?: invalid()
        val resource = JsonFields.optionalObject(panel, "resource")?.let {
            ResourceKey(
                type = JsonFields.string(it, "type"),
                id = JsonFields.string(it, "id"),
            )
        }
        return PanelInstance(
            id = PanelId.parse(JsonFields.string(panel, "id")),
            key = PanelKey.parse(JsonFields.string(panel, "key")),
            resource = resource,
            title = JsonFields.string(panel, "title"),
            dirty = JsonFields.boolean(panel, "dirty"),
            draftRevision = JsonFields.integer(panel, "draftRevision"),
            saveUncertain = (panel["saveUncertain"] as? JsonValue.Bool)?.value ?: false,
        )
    }

    private fun ResourceKey?.toJson(): JsonValue = this?.let {
        JsonValue.obj(
            "type" to JsonValue.text(it.type),
            "id" to JsonValue.text(it.id),
        )
    } ?: JsonValue.Null

    private fun PanelId?.toJson(): JsonValue =
        this?.let { JsonValue.text(it.value) } ?: JsonValue.Null

    private fun mode(value: String): WorkspaceMode =
        WorkspaceMode.entries.firstOrNull { it.name == value } ?: invalid()

    private fun invalid(): Nothing = throw DstuException(
        DstuError.validation("WORKSPACE_SNAPSHOT_INVALID", "invalid workspace layout snapshot"),
    )

    companion object {
        private const val FORMAT_VERSION = 1L
        private const val MAX_CHARS = 4 * 1024 * 1024
    }
}
