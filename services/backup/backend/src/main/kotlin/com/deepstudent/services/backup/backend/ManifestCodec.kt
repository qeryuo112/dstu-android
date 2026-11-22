package com.deepstudent.services.backup.backend

import com.deepstudent.services.backup.api.*
import kotlinx.serialization.json.*

/** Strict v3 wire decoder. It does not decide whether a snapshot is safe to stage. */
object ManifestCodec {
    private val json = Json { isLenient = false; ignoreUnknownKeys = true }

    fun decode(raw: String, checkCancelled: () -> Unit = {}): BackupManifest {
        UniqueJsonKeys(raw, checkCancelled).check()
        checkCancelled()
        val root = json.parseToJsonElement(raw).jsonObject
        checkCancelled()
        return BackupManifest(identity(root), payload(root), snapshot(root), root["coverage"]?.takeUnless { it is JsonNull }?.jsonObject?.let(::coverage))
    }

    private fun identity(root: JsonObject) = ManifestIdentity(
        root.string("version"), root.string("app_version"), root.string("created_at"), root.string("platform"),
        root.optionalString("backup_id") ?: "", root.optionalString("snapshot_epoch") ?: "",
    )

    private fun payload(root: JsonObject) = ManifestPayload(
        root.optionalObject("schema_versions").mapValues { it.value.integer() },
        root.array("files").map { value ->
            val f = value.jsonObject
            BackupFile(f.string("path"), f.long("size"), f.string("sha256"), f.optionalString("database_id"))
        },
        root["assets"]?.takeUnless { it is JsonNull }?.jsonObject?.array("files")?.map { value ->
            val a = value.jsonObject
            BackupAsset(a.string("asset_type"), a.string("relative_path"), a.string("original_path"), a.long("size"), a.optionalString("checksum"), a.optionalBool("is_directory", false))
        } ?: emptyList(),
    )

    private fun snapshot(root: JsonObject) = SnapshotDeclaration(
        root.optionalBool("is_incremental", false), snapshotKind(root.optionalString("snapshot_kind")),
        root.optionalArray("required_components").map { it.text() }, root.optionalArray("included_components").map { it.text() },
        keyPolicy(root.optionalString("key_policy")),
        root["incremental_base"]?.let { it !is JsonNull } ?: false,
    )
    private fun snapshotKind(raw: String?) = when (raw) {
        "full" -> SnapshotKind.FULL
        "partial_overlay" -> SnapshotKind.PARTIAL_OVERLAY
        "legacy_candidate", "legacy_unknown", null -> SnapshotKind.LEGACY_CANDIDATE
        else -> error("unknown snapshot_kind")
    }
    private fun keyPolicy(raw: String?) = when (raw) {
        "included_local" -> KeyPolicy.INCLUDED_LOCAL
        "included_encrypted" -> KeyPolicy.INCLUDED_ENCRYPTED
        "not_present" -> KeyPolicy.NOT_PRESENT
        "excluded_portable" -> KeyPolicy.EXCLUDED_PORTABLE
        "legacy_unknown", null -> KeyPolicy.LEGACY_UNKNOWN
        else -> error("unknown key_policy")
    }
    private fun coverage(obj: JsonObject) = CoverageLedger(
        obj.long("version"), obj.obj("domains").mapValues { (_, value) -> domain(value.jsonObject) },
    )
    private fun domain(c: JsonObject) = DomainCoverage(
        status(c.string("status")), c.optionalArray("paths").map { it.text() }, c.long("file_count"), c.long("total_size"),
        DomainSecurity(c.bool("executable"), c.bool("optional"), c.bool("encrypted"), c.string("restore_target"), scope(c.string("restore_scope")), trust(c.string("restore_trust"))),
    )
    private fun status(raw: String) = when (raw) {
        "complete" -> CoverageStatus.COMPLETE
        "empty" -> CoverageStatus.EMPTY
        "absent" -> CoverageStatus.ABSENT
        "excluded" -> CoverageStatus.EXCLUDED
        "failed" -> CoverageStatus.FAILED
        else -> error("unknown coverage status")
    }
    private fun scope(raw: String) = when (raw) {
        "active_data_space" -> RestoreScope.ACTIVE_DATA_SPACE
        "application_data" -> RestoreScope.APPLICATION_DATA
        "user_home" -> RestoreScope.USER_HOME
        else -> error("unknown restore scope")
    }
    private fun trust(raw: String) = when (raw) {
        "data" -> RestoreTrust.DATA
        "explicit" -> RestoreTrust.EXPLICIT
        "untrusted_executable" -> RestoreTrust.UNTRUSTED_EXECUTABLE
        else -> error("unknown restore trust")
    }
    private fun JsonObject.string(key: String) = getValue(key).text()
    private fun JsonElement.text(): String = jsonPrimitive.let { require(it.isString); it.content }
    private fun JsonObject.optionalString(key: String): String? = this[key]?.takeUnless { it is JsonNull }?.text()
    private fun JsonObject.long(key: String) = getValue(key).integer()
    private fun JsonElement.integer(): Long = jsonPrimitive.let { require(!it.isString); it.long }
    private fun JsonObject.bool(key: String) = getValue(key).jsonPrimitive.let { require(!it.isString); it.boolean }
    private fun JsonObject.optionalBool(key: String, default: Boolean) = if (this[key] == null) default else bool(key)
    private fun JsonObject.array(key: String) = getValue(key).jsonArray
    private fun JsonObject.optionalArray(key: String) = this[key]?.jsonArray ?: JsonArray(emptyList())
    private fun JsonObject.obj(key: String) = getValue(key).jsonObject
    private fun JsonObject.optionalObject(key: String) = this[key]?.jsonObject ?: JsonObject(emptyMap())
}
