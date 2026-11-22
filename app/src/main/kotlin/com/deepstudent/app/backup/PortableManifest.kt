package com.deepstudent.app.backup

import com.deepstudent.services.backup.api.BackupManifest
import com.deepstudent.services.backup.api.KeyPolicy
import com.deepstudent.services.backup.api.SnapshotKind
import com.deepstudent.services.backup.backend.ManifestCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

internal const val PORTABLE_PAYLOAD = "portable_secrets.dsbk"
internal const val PORTABLE_MANIFEST = "manifest.json"
internal const val PORTABLE_CHECKSUMS = "checksums.sha256"
internal const val PORTABLE_MAX_MANIFEST = 16 * 1024 * 1024

/** Frozen desktop portable paths; this is a transport split, never an authorization decision. */
internal fun isPortableExcluded(path: String): Boolean {
    val lower = path.lowercase(Locale.ROOT)
    return lower.substringBefore('/') in setOf("crypto", ".secure", ".master_key", ".key_seed") ||
        lower == "databases/audit.db" || lower == "persistent/user_skills" ||
        lower.startsWith("persistent/user_skills/")
}

internal class PortableManifest private constructor(
    val bytes: ByteArray,
    val parsed: BackupManifest,
    private val root: JsonObject,
) {
    fun payloadDeclaration(): PortablePayload {
        if (parsed.keyPolicy != KeyPolicy.INCLUDED_ENCRYPTED || parsed.snapshotKind != SnapshotKind.PARTIAL_OVERLAY) {
            throw PortableFileException(PortableFileError.OUTER_MANIFEST_INVALID)
        }
        val file = parsed.files.filter { it.path == PORTABLE_PAYLOAD }.singleOrNull()
            ?: throw PortableFileException(PortableFileError.OUTER_MANIFEST_INVALID)
        if (file.databaseId != null || file.size <= 0 || !file.sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            throw PortableFileException(PortableFileError.OUTER_MANIFEST_INVALID)
        }
        return PortablePayload(file.size, file.sha256.lowercase(Locale.ROOT))
    }

    fun requireOriginalPolicy() {
        if (parsed.keyPolicy !in setOf(KeyPolicy.INCLUDED_LOCAL, KeyPolicy.NOT_PRESENT) ||
            parsed.snapshotKind == SnapshotKind.LEGACY_CANDIDATE
        ) throw PortableFileException(PortableFileError.ORIGINAL_MANIFEST_INVALID)
    }

    /** Reproduces the frozen desktop portableManifestObject/markPartial transformation. */
    fun desktopOuter(payload: PortablePayload): ByteArray {
        requireOriginalPolicy()
        val files = root.getValue("files").jsonArray.filterNot { isPortableExcluded(it.jsonObject.text("path")) }
        val payloadEntry = JsonObject(mapOf(
            "path" to JsonPrimitive(PORTABLE_PAYLOAD), "size" to JsonPrimitive(payload.size),
            "sha256" to JsonPrimitive(payload.sha256),
        ))
        val domains = root.getValue("coverage").jsonObject.getValue("domains").jsonObject
        val outerDomains = domains.mapValues { (_, value) -> excludedDomain(value.jsonObject) }
        val assets = root["assets"]?.takeUnless { it.toString() == "null" }?.jsonObject?.let(::outerAssets)
        val components = includedComponents(files, assets, outerDomains)
        val out = root.toMutableMap()
        out["files"] = JsonArray(files + payloadEntry)
        out["key_policy"] = JsonPrimitive("included_encrypted")
        out["snapshot_kind"] = JsonPrimitive("partial_overlay")
        out["required_components"] = JsonArray(emptyList())
        out["included_components"] = JsonArray(components.sorted().map(::JsonPrimitive))
        out["coverage"] = JsonObject(root.getValue("coverage").jsonObject.toMutableMap().apply {
            put("domains", JsonObject(outerDomains))
        })
        if (assets != null) out["assets"] = assets
        return JsonObject(out).toString().toByteArray(Charsets.UTF_8)
    }

    private fun excludedDomain(domain: JsonObject): JsonObject {
        val encrypted = domain.getValue("encrypted").jsonPrimitive.boolean
        val target = domain.text("restore_target")
        if (!encrypted && target != "databases/audit.db") return domain
        return JsonObject(domain.toMutableMap().apply {
            put("status", JsonPrimitive("excluded")); put("paths", JsonArray(emptyList()))
            put("file_count", JsonPrimitive(0)); put("total_size", JsonPrimitive(0))
            put("detail", JsonPrimitive("sealed into password-encrypted portable payload"))
        })
    }

    private fun outerAssets(assets: JsonObject): JsonObject {
        val files = assets.getValue("files").jsonArray.filterNot {
            val asset = it.jsonObject
            isPortableExcluded(asset.text("relative_path")) || isPortableExcluded(asset.text("original_path"))
        }
        val total = files.fold(0L) { count, value -> Math.addExact(count, value.jsonObject.getValue("size").jsonPrimitive.long) }
        return JsonObject(assets.toMutableMap().apply {
            put("files", JsonArray(files)); put("total_files", JsonPrimitive(files.size)); put("total_size", JsonPrimitive(total))
        })
    }

    private fun includedComponents(files: List<JsonElement>, assets: JsonObject?, domains: Map<String, JsonElement>): Set<String> {
        val components = mutableSetOf<String>()
        files.mapNotNull { fileComponent(it.jsonObject) }.forEach(components::add)
        assets?.getValue("files")?.jsonArray?.forEach { components += "asset-root:${it.jsonObject.text("asset_type")}" }
        domains.forEach { (id, value) ->
            if (value.jsonObject.text("status") in setOf("complete", "empty", "absent")) components += id
        }
        return components
    }

    private fun fileComponent(file: JsonObject): String? {
        val id = file["database_id"]?.jsonPrimitive?.contentOrNull
        if (id != null) return "database:$id"
        val path = file.text("path")
        return when {
            path.startsWith("workspaces/") -> componentFor(path, "workspaces/", "workspace:")
            path.startsWith("assets/") -> componentFor(path, "assets/", "asset-root:")
            else -> null
        }
    }

    private fun componentFor(path: String, prefix: String, component: String): String? =
        path.removePrefix(prefix).substringBefore('/').takeIf(String::isNotEmpty)?.let { component + it }

    companion object {
        fun parse(bytes: ByteArray, check: () -> Unit): PortableManifest {
            if (bytes.size > PORTABLE_MAX_MANIFEST) throw PortableFileException(PortableFileError.ORIGINAL_MANIFEST_INVALID)
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            // ManifestCodec checks unique keys recursively and agrees with the real planner's decoder.
            val parsed = ManifestCodec.decode(text, check)
            return PortableManifest(bytes, parsed, Json.parseToJsonElement(text).jsonObject)
        }
    }
}

internal data class PortablePayload(val size: Long, val sha256: String)
private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.let { require(it.isString); it.content }
