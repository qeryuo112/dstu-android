package com.deepstudent.features.cards.api

import com.deepstudent.core.common.json.JsonFields
import com.deepstudent.core.common.json.JsonValue

object TemplateCodec {
    fun decodeChangeRecord(value: JsonValue.Object): TemplateChangeRecord {
        val tableName = JsonFields.string(value, "table_name")
        val recordId = JsonFields.string(value, "record_id")
        val operation = TemplateOperation.fromWire(JsonFields.string(value, "operation"))
        val data = JsonFields.objectValue(value, "data")
        val changedAt = JsonFields.string(value, "changed_at")
        val databaseName = JsonFields.string(value, "database_name")
        val extra = extraFields(value, TemplateContract.CHANGE_ROOT_FIELDS.toSet())
        return TemplateChangeRecord(
            tableName = tableName,
            recordId = recordId,
            operation = operation,
            data = decodeRow(data),
            changedAt = changedAt,
            databaseName = databaseName,
            extraRoot = extra,
        )
    }

    fun decodeChangeRecord(text: String): TemplateChangeRecord =
        decodeChangeRecord(JsonValue.parseObject(text, "template change"))

    fun encodeChangeRecord(record: TemplateChangeRecord): String {
        val builder = JsonValue.Object.Builder()
            .put("table_name", record.tableName)
            .put("record_id", record.recordId)
            .put("operation", record.operation.wire)
            .put("data", encodeRowObject(record.data))
            .put("changed_at", record.changedAt)
            .put("database_name", record.databaseName)
        builder.putAll(record.extraRoot)
        return builder.build().literal()
    }

    fun decodeRow(value: JsonValue.Object): TemplateRow {
        requirePresent(value, TemplateContract.ROW_FIELDS)
        return TemplateRow(
            identity = TemplateIdentity(
                id = JsonFields.string(value, "id"),
                name = JsonFields.string(value, "name"),
            ),
            content = TemplateContent(
                description = JsonFields.optionalString(value, "description"),
                author = JsonFields.optionalString(value, "author"),
                version = JsonFields.string(value, "version"),
                fieldsJson = JsonFields.string(value, "fields_json"),
                generationPrompt = JsonFields.string(value, "generation_prompt"),
                frontTemplate = JsonFields.string(value, "front_template"),
                backTemplate = JsonFields.string(value, "back_template"),
                fieldExtractionRulesJson = JsonFields.string(value, "field_extraction_rules_json"),
            ),
            presentation = TemplatePresentation(
                previewFront = JsonFields.string(value, "preview_front"),
                previewBack = JsonFields.string(value, "preview_back"),
                noteType = JsonFields.string(value, "note_type"),
                cssStyle = JsonFields.string(value, "css_style"),
                previewDataJson = JsonFields.optionalString(value, "preview_data_json"),
            ),
            userState = TemplateUserState(
                isBuiltIn = intValue(value, "is_built_in"),
                userModified = intValue(value, "user_modified"),
                userDeleted = intValue(value, "user_deleted"),
            ),
            syncState = TemplateSyncState(
                createdAt = JsonFields.string(value, "created_at"),
                updatedAt = JsonFields.string(value, "updated_at"),
                isActive = intValue(value, "is_active"),
                deviceId = JsonFields.optionalString(value, "device_id"),
                localVersion = JsonFields.optionalInteger(value, "local_version"),
                deletedAt = JsonFields.optionalString(value, "deleted_at"),
            ),
            unknownFields = extraFields(value, TemplateContract.ROW_FIELDS.toSet()),
        )
    }

    fun encodeRow(row: TemplateRow): String = encodeRowObject(row).literal()

    fun encodeRowObject(row: TemplateRow): JsonValue.Object {
        val builder = JsonValue.Object.Builder()
            .put("id", row.id)
            .put("name", row.name)
            .put("description", row.description?.let(JsonValue::text) ?: JsonValue.Null)
            .put("author", row.author?.let(JsonValue::text) ?: JsonValue.Null)
            .put("version", row.version)
            .put("preview_front", row.previewFront)
            .put("preview_back", row.previewBack)
            .put("note_type", row.noteType)
            .put("fields_json", row.fieldsJson)
            .put("generation_prompt", row.generationPrompt)
            .put("front_template", row.frontTemplate)
            .put("back_template", row.backTemplate)
            .put("css_style", row.cssStyle)
            .put("field_extraction_rules_json", row.fieldExtractionRulesJson)
            .put("created_at", row.createdAt)
            .put("updated_at", row.updatedAt)
            .put("is_active", row.isActive)
            .put("is_built_in", row.isBuiltIn)
            .put("preview_data_json", row.previewDataJson?.let(JsonValue::text) ?: JsonValue.Null)
            .put("user_modified", row.userModified)
            .put("user_deleted", row.userDeleted)
            .put("device_id", row.deviceId?.let(JsonValue::text) ?: JsonValue.Null)
            .put("local_version", row.localVersion?.let(JsonValue::integer) ?: JsonValue.Null)
            .put("deleted_at", row.deletedAt?.let(JsonValue::text) ?: JsonValue.Null)
        builder.putAll(row.unknownFields)
        return builder.build()
    }

    private fun intValue(value: JsonValue.Object, key: String): Int {
        val number = JsonFields.integer(value, key)
        if (number < Int.MIN_VALUE || number > Int.MAX_VALUE) {
            templateError("INVALID_TEMPLATE_INTEGER", "$key is outside the 32-bit integer domain")
        }
        return number.toInt()
    }

    private fun extraFields(value: JsonValue.Object, known: Set<String>): JsonValue.Object {
        val builder = JsonValue.Object.Builder()
        for (key in value.keys()) if (key !in known) builder.put(key, value[key] ?: JsonValue.Null)
        return builder.build()
    }

    private fun requirePresent(value: JsonValue.Object, fields: List<String>) {
        for (field in fields) {
            if (!value.containsKey(field)) {
                templateError("INVALID_TEMPLATE_ROW", "template row is missing $field")
            }
        }
    }
}
