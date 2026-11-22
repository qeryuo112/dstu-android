package com.deepstudent.features.workspace.data

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.json.JsonFields
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.workspace.api.PanelDraft
import com.deepstudent.features.workspace.api.PanelId
import java.util.Base64

internal object DraftFileCodec {
    private const val VERSION = 1L
    private const val MAX_CHARS = PanelDraft.MAX_BYTES * 2L

    fun encode(draft: PanelDraft): ByteArray {
        val bytes = draft.bytes
        return JsonValue.obj(
            "version" to JsonValue.integer(VERSION),
            "panelId" to JsonValue.text(draft.panelId.value),
            "revision" to JsonValue.integer(draft.revision),
            "updatedAtEpochMs" to JsonValue.integer(draft.updatedAtEpochMs),
            "checksum" to JsonValue.text(Digests.sha256Hex(bytes)),
            "bytes" to JsonValue.text(Base64.getEncoder().encodeToString(bytes)),
        ).canonical().toByteArray(Charsets.UTF_8)
    }

    fun decode(encoded: ByteArray): PanelDraft {
        if (encoded.size > MAX_CHARS) corrupt()
        return try {
            decodeObject(JsonValue.parseObject(encoded.toString(Charsets.UTF_8), "workspace draft"))
        } catch (failure: Exception) {
            corrupt()
        }
    }

    private fun decodeObject(value: JsonValue.Object): PanelDraft {
        if (JsonFields.integer(value, "version") != VERSION) corrupt()
        val payload = Base64.getDecoder().decode(JsonFields.string(value, "bytes"))
        if (payload.size > PanelDraft.MAX_BYTES) corrupt()
        if (Digests.sha256Hex(payload) != JsonFields.string(value, "checksum")) corrupt()
        return PanelDraft(
            panelId = PanelId.parse(JsonFields.string(value, "panelId")),
            bytes = payload,
            revision = JsonFields.integer(value, "revision"),
            updatedAtEpochMs = JsonFields.integer(value, "updatedAtEpochMs"),
        )
    }

    fun corrupt(): Nothing = throw DstuException(
        DstuError.validation("WORKSPACE_DRAFT_CORRUPT", "workspace draft is corrupt"),
    )
}
