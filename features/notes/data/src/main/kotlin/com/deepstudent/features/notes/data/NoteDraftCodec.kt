package com.deepstudent.features.notes.data

import com.deepstudent.core.common.id.ResourceId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.notes.api.NoteDocument
import com.deepstudent.features.notes.api.NoteDraftSnapshot
import com.deepstudent.features.notes.api.NoteMetadata
import com.deepstudent.features.notes.api.NoteTimes

object NoteDraftCodec {
    fun encode(draft: NoteDraftSnapshot): String = JsonValue.Object.Builder()
        .put("version", 1).put("original", draft.original?.let(::encodeNote) ?: JsonValue.Null)
        .put("title", draft.title).put("content", draft.content).put("tags", tags(draft.tags))
        .put("editorKey", draft.editorKey).build().literal()

    fun decode(source: String): NoteDraftSnapshot {
        require(source.toByteArray(Charsets.UTF_8).size <= 4 * 1024 * 1024) { "draft budget exceeded" }
        val obj = JsonValue.parseObject(source)
        require((obj["version"] as? JsonValue.Number)?.asLongOrNull() == 1L) { "unknown draft version" }
        val original = when (val value = obj["original"]) {
            JsonValue.Null -> null
            is JsonValue.Object -> decodeNote(value)
            else -> error("invalid draft original")
        }
        val key = (obj["editorKey"] as? JsonValue.Number)?.asLongOrNull() ?: error("invalid editor identity")
        require(key > 0)
        return NoteDraftSnapshot(original, obj.text("title"), obj.text("content"), obj.tags(), key)
    }

    private fun encodeNote(note: NoteDocument): JsonValue.Object = JsonValue.Object.Builder()
        .put("id", note.id).put("resourceId", note.resourceId.value).put("title", note.title)
        .put("content", note.content).put("tags", tags(note.tags)).put("favorite", note.isFavorite)
        .put("createdAt", note.createdAt).put("updatedAt", note.updatedAt)
        .put("deletedAt", note.deletedAt?.let(JsonValue::Text) ?: JsonValue.Null)
        .put("props", note.props ?: JsonValue.Null).build()

    private fun decodeNote(obj: JsonValue.Object): NoteDocument = NoteDocument(
        obj.text("id"), ResourceId.parse(obj.text("resourceId")), obj.text("title"), obj.text("content"), obj.tags(),
        NoteMetadata((obj["favorite"] as? JsonValue.Bool)?.value ?: error("invalid favorite"),
            NoteTimes(obj.text("createdAt"), obj.text("updatedAt"), (obj["deletedAt"] as? JsonValue.Text)?.value),
            obj["props"]?.takeUnless { it == JsonValue.Null }),
    )

    private fun tags(values: List<String>) = JsonValue.Array(values.map(JsonValue::Text))
    private fun JsonValue.Object.text(key: String): String = (this[key] as? JsonValue.Text)?.value ?: error("invalid draft field")
    private fun JsonValue.Object.tags(): List<String> = (this["tags"] as? JsonValue.Array)?.values
        ?.map { (it as? JsonValue.Text)?.value ?: error("invalid draft tag") } ?: error("invalid draft tags")
}
