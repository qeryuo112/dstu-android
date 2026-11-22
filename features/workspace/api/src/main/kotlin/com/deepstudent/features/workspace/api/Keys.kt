package com.deepstudent.features.workspace.api

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.json.JsonValue

private val panelKeyPattern = Regex("[A-Za-z0-9._:-]{1,64}")
private val panelIdPattern = Regex("[A-Za-z0-9._:-]{1,128}")
private val resourceTypePattern = Regex("[A-Za-z0-9._:-]{1,64}")

@JvmInline
value class PanelKey private constructor(val value: String) {
    companion object {
        fun parse(value: String): PanelKey {
            if (!panelKeyPattern.matches(value)) invalid("INVALID_PANEL_KEY")
            return PanelKey(value)
        }
    }
}

@JvmInline
value class PanelId private constructor(val value: String) {
    companion object {
        fun parse(value: String): PanelId {
            if (!panelIdPattern.matches(value)) invalid("INVALID_PANEL_ID")
            return PanelId(value)
        }
    }
}

/** Stable identity for repeated resource activation. It is not a filesystem path. */
data class ResourceKey(val type: String, val id: String) {
    init {
        if (!resourceTypePattern.matches(type) || !validResourceId(id)) {
            invalid("INVALID_RESOURCE_KEY")
        }
    }

    /** Unambiguous canonical identity; JSON array escaping avoids delimiter collisions. */
    val canonical: String
        get() = JsonValue.array(listOf(JsonValue.text(type), JsonValue.text(id))).canonical()
}

object WorkspacePanelKeys {
    val Notes: PanelKey = PanelKey.parse("notes")
    val Pdf: PanelKey = PanelKey.parse("pdf")
    val Chat: PanelKey = PanelKey.parse("chat")
    val Browser: PanelKey = PanelKey.parse("browser")
}

private fun validResourceId(value: String): Boolean {
    val bytes = value.toByteArray(Charsets.UTF_8)
    return bytes.isNotEmpty() && bytes.size <= 4096 &&
        value.none { it.code < 0x20 || it.code == 0x7f }
}

private fun invalid(code: String): Nothing =
    throw DstuException(DstuError.validation(code, "invalid workspace identity"))
