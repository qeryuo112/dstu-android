package com.deepstudent.features.notes.data

import com.deepstudent.core.common.json.JsonValue

internal fun syncMergeFields(table: String, local: JsonValue.Object?, incoming: MutableMap<String, JsonValue>) {
    if (local == null || table !in setOf("notes", "files")) return
    if ("is_favorite" in incoming) syncMergeFavorite(local, incoming)
    syncMergeTags(table, local, incoming)
}

private fun syncMergeTags(table: String, local: JsonValue.Object, incoming: MutableMap<String, JsonValue>) {
    val key = if (table == "notes") "tags" else "tags_json"
    if (key !in incoming || incoming[key] == JsonValue.Null) return
    val old = syncTags(local[key])
    val new = syncTags(incoming[key])
    if (old.isEmpty() && new.isEmpty()) return
    val tags = (old + new).distinct().sortedWith(::syncUtf8Compare)
    val normalized = if (table == "notes") syncNormalizeTags(tags) else tags
    incoming[key] = JsonValue.text(JsonValue.array(normalized.map(JsonValue::text)).literal())
}

private fun syncNormalizeTags(tags: List<String>): List<String> {
    val numeric = listOf("_hits:", "_last_hit:", "_last_injected:", "_used:")
    val enums = listOf("_type:", "_purpose:", "_daily_log_date:")
    val keep = mutableSetOf<String>()
    for (prefix in numeric) {
        val candidates = tags.filter { it.startsWith(prefix) }
        val winner = candidates.maxWithOrNull(compareBy<String> { it.removePrefix(prefix).toLongOrNull() != null }
            .thenBy { it.removePrefix(prefix).toLongOrNull() ?: 0L }.thenComparator(::syncUtf8Compare))
        winner?.let(keep::add)
    }
    for (prefix in enums) tags.firstOrNull { it.startsWith(prefix) }?.let(keep::add)
    return tags.filter { tag -> (numeric + enums).none { tag.startsWith(it) } || tag in keep }
}

private fun syncMergeFavorite(local: JsonValue.Object, incoming: MutableMap<String, JsonValue>) {
    val favorite = (local["is_favorite"] as? JsonValue.Number)?.asLongOrNull() ?: 0
    val remote = (incoming["is_favorite"] as? JsonValue.Number)?.asLongOrNull() ?: 0
    if (favorite != 0L || remote != 0L) incoming["is_favorite"] = JsonValue.integer(1)
}

private fun syncTags(value: JsonValue?): List<String> {
    val array = when (value) {
        is JsonValue.Text -> runCatching { JsonValue.parse(value.value) as? JsonValue.Array }.getOrNull()
        is JsonValue.Array -> value
        else -> null
    } ?: return emptyList()
    return array.values.mapNotNull { (it as? JsonValue.Text)?.value }
}
