package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import java.util.Locale

/** Moonshot MFJS dialect. Applies only to declared tool schemas, never to tool-result data. */
internal fun normalizeMoonshotToolSchema(schema: JsonValue.Object): JsonValue.Object = normalizeMoonshotNode(schema, isRoot = true)

internal fun usesMoonshotToolSchema(model: String, provider: String, adapter: String, baseUrl: String): Boolean =
    provider in MOONSHOT_IDS || adapter in MOONSHOT_IDS ||
        baseUrl.contains("moonshot.cn") || baseUrl.contains("api.kimi.com") ||
        model.lowercase(Locale.ROOT).let { "kimi" in it || "moonshot" in it }

private fun normalizeMoonshotNode(schema: JsonValue.Object, isRoot: Boolean): JsonValue.Object {
    val node = LinkedHashMap(schema.asMap())
    applyAlternatives(node)
    applyEnum(node)
    node.keys.retainAll(MFJS_KEYS)
    applySiblingConstraints(node, isRoot)
    for (key in listOf("properties", "\$defs")) {
        val children = node[key] as? JsonValue.Object ?: continue
        node[key] = JsonValue.Object.from(children.asMap().mapValues { (_, child) -> normalizeChild(child) })
    }
    for (key in listOf("items", "additionalProperties", "anyOf")) {
        node[key]?.let { node[key] = normalizeChildren(it) }
    }
    return JsonValue.Object.from(node)
}

private fun normalizeChild(value: JsonValue): JsonValue =
    if (value is JsonValue.Object) normalizeMoonshotNode(value, isRoot = false) else value

private fun normalizeChildren(value: JsonValue): JsonValue =
    if (value is JsonValue.Array) JsonValue.array(value.values.map(::normalizeChild)) else normalizeChild(value)

private fun applyAlternatives(node: MutableMap<String, JsonValue>) {
    val oneOf = node.remove("oneOf") as? JsonValue.Array ?: return
    val existing = (node["anyOf"] as? JsonValue.Array)?.values.orEmpty()
    node["anyOf"] = JsonValue.array(existing + oneOf.values)
}

private fun applyEnum(node: MutableMap<String, JsonValue>) {
    node.remove("const")?.let { node.putIfAbsent("enum", JsonValue.array(listOf(it))) }
    val values = (node["enum"] as? JsonValue.Array)?.values?.takeIf { it.isNotEmpty() } ?: return
    if (values.all { it == JsonValue.Null }) {
        node.remove("enum")
        node["type"] = JsonValue.text("null")
    } else if (!node.containsKey("type")) {
        enumType(values)?.let { node["type"] = JsonValue.text(it) }
    }
}

private fun enumType(values: List<JsonValue>): String? = when {
    values.all { it is JsonValue.Text } -> "string"
    values.all { it is JsonValue.Bool } -> "boolean"
    values.all { it is JsonValue.Number } -> if (values.all(::isIntegral)) "integer" else "number"
    else -> null
}

private fun isIntegral(value: JsonValue): Boolean =
    (value as JsonValue.Number).literal.toBigDecimalOrNull()?.stripTrailingZeros()?.let { it.scale() <= 0 } == true

private fun applySiblingConstraints(node: MutableMap<String, JsonValue>, isRoot: Boolean) {
    val choices = (node["anyOf"] as? JsonValue.Array)?.values?.takeIf { it.isNotEmpty() } ?: return
    val branches = choices.filterIsInstance<JsonValue.Object>()
    if (choices.size != branches.size) return
    for (key in ANNOTATION_KEYS) {
        if (branches.any { it.containsKey(key) }) node.remove(key)
    }
    val outer = node.filterKeys { it != "anyOf" && it !in ANNOTATION_KEYS && (!isRoot || it !in ROOT_KEYS) }
    if (outer.isEmpty()) return
    node["anyOf"] = JsonValue.array(branches.map { mergeBranch(it, outer) })
    node.keys.removeAll(outer.keys)
}

private fun mergeBranch(branch: JsonValue.Object, outer: Map<String, JsonValue>): JsonValue.Object {
    val merged = LinkedHashMap(branch.asMap())
    outer.forEach { (key, fromParent) ->
        val prior = merged[key]
        merged[key] = when {
            key == "required" && prior is JsonValue.Array -> mergeRequired(prior, fromParent)
            key == "properties" && prior is JsonValue.Object -> mergeProperties(prior, fromParent)
            else -> prior ?: fromParent
        }
    }
    return JsonValue.Object.from(merged)
}

private fun mergeRequired(prior: JsonValue.Array, parent: JsonValue): JsonValue =
    if (parent is JsonValue.Array) JsonValue.array((prior.values + parent.values).distinct()) else prior

private fun mergeProperties(prior: JsonValue.Object, parent: JsonValue): JsonValue =
    if (parent is JsonValue.Object) JsonValue.Object.from(parent.asMap() + prior.asMap()) else prior

private val MOONSHOT_IDS = setOf("moonshot", "kimi")
private val ROOT_KEYS = setOf("\$defs", "\$id")
private val ANNOTATION_KEYS = setOf("title", "description")
private val MFJS_KEYS = setOf(
    "type", "properties", "additionalProperties", "items", "enum", "required", "anyOf", "description", "\$defs", "\$ref",
    "title", "\$id", "default", "maxLength", "minLength", "maximum", "minimum", "maxItems", "minItems", "pattern",
)
