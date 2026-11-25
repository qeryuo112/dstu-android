package com.deepstudent.services.sync.data.apply

import com.deepstudent.core.common.json.JsonValue

/** The frozen desktop's basic field rules; only applied for a row with a local pending edit. */
internal object IncomingFieldMerge {
    private val tags = mapOf(
        "questions" to "tags",
        "todo_items" to "tags_json",
        "mistakes" to "tags",
        "review_analyses" to "tags",
        "anki_cards" to "tags_json",
    )
    private val favorites = setOf("questions", "essays", "translations", "todo_lists", "mindmaps", "exam_sheets")
    fun merge(table: String, local: JsonValue.Object, remote: MutableMap<String, JsonValue>) {
        mergeTags(table, local, remote)
        if (table in favorites) mergeBoolean(local, remote, "is_favorite")
        if (table == "questions") mergeBoolean(local, remote, "is_bookmarked")
        if (table == "review_plans") mergeReviewCounts(local, remote)
    }

    private fun mergeTags(table: String, local: JsonValue.Object, remote: MutableMap<String, JsonValue>) {
        val key = tags[table] ?: return
        val left = strings(local[key])
        val right = strings(remote[key])
        if (left.isEmpty() && right.isEmpty()) return
        val merged = (left + right).distinct().sortedWith { a, b -> incomingTextOrder(a, b) }
        remote[key] = JsonValue.text(JsonValue.array(merged.map(JsonValue::text)).literal())
    }

    private fun mergeReviewCounts(local: JsonValue.Object, remote: MutableMap<String, JsonValue>) {
        listOf("total_reviews", "total_correct").forEach { key ->
            val a = (local[key] as? JsonValue.Number)?.asLongOrNull()
            val b = (remote[key] as? JsonValue.Number)?.asLongOrNull()
            if (a != null && b != null) remote[key] = JsonValue.integer(maxOf(a, b))
        }
    }

    private fun mergeBoolean(local: JsonValue.Object, remote: MutableMap<String, JsonValue>, key: String) {
        val a = (local[key] as? JsonValue.Number)?.asLongOrNull()
        val b = (remote[key] as? JsonValue.Number)?.asLongOrNull()
        if (a != null && b != null) remote[key] = JsonValue.integer(if (a != 0L || b != 0L) 1 else 0)
    }

    private fun strings(value: JsonValue?): List<String> {
        val array = when (value) {
            is JsonValue.Text -> try {
                JsonValue.parse(value.value) as? JsonValue.Array
            } catch (_: Exception) {
                null
            }

            is JsonValue.Array -> value

            else -> null
        } ?: return emptyList()
        return array.values.mapNotNull { (it as? JsonValue.Text)?.value }
    }
}

private fun incomingTextOrder(left: String, right: String): Int {
    val a = left.toByteArray(Charsets.UTF_8)
    val b = right.toByteArray(Charsets.UTF_8)
    for (i in 0 until minOf(a.size, b.size)) {
        val difference = (a[i].toInt() and 255) - (b[i].toInt() and 255)
        if (difference != 0) return difference
    }
    return a.size.compareTo(b.size)
}
