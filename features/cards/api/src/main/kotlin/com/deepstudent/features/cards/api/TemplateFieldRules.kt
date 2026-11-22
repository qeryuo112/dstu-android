package com.deepstudent.features.cards.api

import com.deepstudent.core.common.json.JsonValue

data class TemplateFieldRule(
    val fieldType: String,
    val required: Boolean,
    val defaultValue: String? = null,
    val validationPattern: String? = null,
    val description: String,
) {
    init {
        if (fieldType.isBlank()) templateError("INVALID_FIELD_RULE", "field_type must not be blank")
    }

    fun encode(): JsonValue.Object {
        val builder = JsonValue.Object.Builder()
            .put("field_type", fieldType)
            .put("is_required", required)
            .put("default_value", defaultValue?.let(JsonValue::text) ?: JsonValue.Null)
            .put("validation_pattern", validationPattern?.let(JsonValue::text) ?: JsonValue.Null)
            .put("description", description)
        return builder.build()
    }

    companion object {
        fun decode(value: JsonValue.Object): TemplateFieldRule = TemplateFieldRule(
            fieldType = requiredText(value, "field_type"),
            required = requiredBoolean(value, "is_required"),
            defaultValue = optionalText(value, "default_value"),
            validationPattern = optionalText(value, "validation_pattern"),
            description = requiredText(value, "description"),
        )

        private fun requiredText(value: JsonValue.Object, key: String): String =
            (value[key] as? JsonValue.Text)?.value
                ?: templateError("INVALID_FIELD_RULE", "$key is required")

        private fun requiredBoolean(value: JsonValue.Object, key: String): Boolean =
            (value[key] as? JsonValue.Bool)?.value
                ?: templateError("INVALID_FIELD_RULE", "$key is required")

        private fun optionalText(value: JsonValue.Object, key: String): String? =
            when (val item = value[key]) {
                null, JsonValue.Null -> null
                is JsonValue.Text -> item.value
                else -> templateError("INVALID_FIELD_RULE", "$key must be a string or null")
            }
    }
}

object TemplateFieldRules {
    fun encode(rules: Map<String, TemplateFieldRule>): String {
        val builder = JsonValue.Object.Builder()
        for ((name, rule) in rules) builder.put(name, rule.encode())
        return builder.build().literal()
    }

    fun decode(text: String): Map<String, TemplateFieldRule> {
        val value = JsonValue.parse(text)
        val objectValue = value as? JsonValue.Object
            ?: templateError("INVALID_TEMPLATE_RULES", "field_extraction_rules_json must be an object")
        val result = LinkedHashMap<String, TemplateFieldRule>()
        for (name in objectValue.keys()) {
            val item = objectValue[name] as? JsonValue.Object
                ?: templateError("INVALID_TEMPLATE_RULES", "rule $name must be an object")
            result[name] = TemplateFieldRule.decode(item)
        }
        return result
    }

    fun encodeFields(fields: List<String>): String =
        JsonValue.Array(fields.map(JsonValue::text)).literal()
}
