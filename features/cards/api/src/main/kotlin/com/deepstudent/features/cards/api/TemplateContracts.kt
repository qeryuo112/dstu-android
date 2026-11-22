package com.deepstudent.features.cards.api

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.json.JsonValue

object TemplateContract {
    const val TABLE = "custom_anki_templates"
    const val DATABASE = "mistakes"
    const val MIGRATION_VERSION = 20261006L
    const val MIGRATION_FILE = "mistakes/V20261006__portable_anki_templates.sql"
    const val MIGRATION_SHA256 = "205f7da3f916b65c56b20bdd1472213837dcf359cc25cfe2697a6a3bbf6646d9"
    const val CHANGE_LOG_TABLE = "__change_log"
    const val CAPTURE_PREDICATE = "is_built_in = 0 OR user_modified = 1 OR user_deleted = 1"

    val ROW_FIELDS: List<String> = listOf(
        "id",
        "name",
        "description",
        "author",
        "version",
        "preview_front",
        "preview_back",
        "note_type",
        "fields_json",
        "generation_prompt",
        "front_template",
        "back_template",
        "css_style",
        "field_extraction_rules_json",
        "created_at",
        "updated_at",
        "is_active",
        "is_built_in",
        "preview_data_json",
        "user_modified",
        "user_deleted",
        "device_id",
        "local_version",
        "deleted_at",
    )

    val CHANGE_ROOT_FIELDS: List<String> = listOf(
        "table_name",
        "record_id",
        "operation",
        "data",
        "changed_at",
        "database_name",
    )
}

enum class TemplateOperation(val wire: String) {
    INSERT("Insert"),
    UPDATE("Update"),
    DELETE("Delete"),
    ;

    companion object {
        fun fromWire(value: String): TemplateOperation =
            entries.firstOrNull { it.wire == value }
                ?: templateError("INVALID_TEMPLATE_OPERATION", "unknown template operation $value")
    }
}

data class TemplateIdentity(
    val id: String,
    val name: String,
)

data class TemplateContent(
    val description: String?,
    val author: String?,
    val version: String,
    val fieldsJson: String,
    val generationPrompt: String,
    val frontTemplate: String,
    val backTemplate: String,
    val fieldExtractionRulesJson: String,
)

data class TemplatePresentation(
    val previewFront: String,
    val previewBack: String,
    val noteType: String,
    val cssStyle: String,
    val previewDataJson: String?,
)

data class TemplateUserState(
    val isBuiltIn: Int,
    val userModified: Int,
    val userDeleted: Int,
)

data class TemplateSyncState(
    val createdAt: String,
    val updatedAt: String,
    val isActive: Int,
    val deviceId: String?,
    val localVersion: Long?,
    val deletedAt: String?,
)

data class TemplateRow(
    val identity: TemplateIdentity,
    val content: TemplateContent,
    val presentation: TemplatePresentation,
    val userState: TemplateUserState,
    val syncState: TemplateSyncState,
    val unknownFields: JsonValue.Object = JsonValue.Object.EMPTY,
) {
    init {
        if (unknownFields.keys().any { it in TemplateContract.ROW_FIELDS }) {
            templateError("INVALID_TEMPLATE_UNKNOWN", "unknown fields must not shadow known template columns")
        }
        TemplateValidation.validateJsonText(content.fieldsJson, "fields_json")
        TemplateValidation.validateJsonText(content.fieldExtractionRulesJson, "field_extraction_rules_json")
        presentation.previewDataJson?.let { TemplateValidation.validateJsonText(it, "preview_data_json") }
    }

    val id: String get() = identity.id
    val name: String get() = identity.name
    val description: String? get() = content.description
    val author: String? get() = content.author
    val version: String get() = content.version
    val fieldsJson: String get() = content.fieldsJson
    val generationPrompt: String get() = content.generationPrompt
    val frontTemplate: String get() = content.frontTemplate
    val backTemplate: String get() = content.backTemplate
    val fieldExtractionRulesJson: String get() = content.fieldExtractionRulesJson
    val previewFront: String get() = presentation.previewFront
    val previewBack: String get() = presentation.previewBack
    val noteType: String get() = presentation.noteType
    val cssStyle: String get() = presentation.cssStyle
    val previewDataJson: String? get() = presentation.previewDataJson
    val isBuiltIn: Int get() = userState.isBuiltIn
    val userModified: Int get() = userState.userModified
    val userDeleted: Int get() = userState.userDeleted
    val createdAt: String get() = syncState.createdAt
    val updatedAt: String get() = syncState.updatedAt
    val isActive: Int get() = syncState.isActive
    val deviceId: String? get() = syncState.deviceId
    val localVersion: Long? get() = syncState.localVersion
    val deletedAt: String? get() = syncState.deletedAt

    /**
     * SQL trigger predicate. Only user-created or user-state rows are captured;
     * untouched bundled templates remain device-local.
     */
    val captureEligible: Boolean
        get() = isBuiltIn == 0 || userModified == 1 || userDeleted == 1

    /**
     * Template HTML/CSS is opaque data. There is deliberately no evaluator and
     * no trust flag derived from row contents.
     */
    val executable: Boolean get() = false
}

data class TemplateChangeRecord(
    val tableName: String,
    val recordId: String,
    val operation: TemplateOperation,
    val data: TemplateRow,
    val changedAt: String,
    val databaseName: String,
    val extraRoot: JsonValue.Object = JsonValue.Object.EMPTY,
) {
    init {
        if (tableName != TemplateContract.TABLE) {
            templateError("INVALID_TEMPLATE_TABLE", "template change table must be ${TemplateContract.TABLE}")
        }
        if (databaseName != TemplateContract.DATABASE) {
            templateError("INVALID_TEMPLATE_DATABASE", "template change database must be ${TemplateContract.DATABASE}")
        }
        if (recordId != data.id) {
            templateError("INVALID_TEMPLATE_RECORD", "record_id must equal data.id")
        }
        if (extraRoot.keys().any { it in TemplateContract.CHANGE_ROOT_FIELDS }) {
            templateError("INVALID_TEMPLATE_ROOT", "extra root fields must not shadow change envelope fields")
        }
    }
}

object TemplateValidation {
    fun validateJsonText(text: String, field: String) {
        try {
            JsonValue.parse(text)
        } catch (failure: DstuException) {
            templateError("INVALID_TEMPLATE_JSON", "$field is not valid JSON")
        }
    }

    fun validateFieldsArray(text: String) {
        when (JsonValue.parse(text)) {
            is JsonValue.Array -> Unit
            else -> templateError("INVALID_TEMPLATE_FIELDS", "fields_json must be a JSON array")
        }
    }

    fun validateRulesObject(text: String) {
        when (JsonValue.parse(text)) {
            is JsonValue.Object -> Unit
            else -> templateError("INVALID_TEMPLATE_RULES", "field_extraction_rules_json must be a JSON object")
        }
    }
}

fun templateError(code: String, message: String): Nothing =
    throw DstuException(DstuError.validation(code, message))
