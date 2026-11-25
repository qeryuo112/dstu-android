package com.deepstudent.services.sync.data.apply

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.SqlOperations

/** Special invariants run before generic LWW, including when an incoming record would be stale. */
interface IncomingRowPolicy {
    fun validate(operation: String, data: JsonValue.Object?) = Unit
    fun verify(sql: SqlOperations, operation: String, data: JsonValue.Object?, local: JsonValue.Object?) = Unit
    fun merge(data: JsonValue.Object, local: JsonValue.Object?): JsonValue.Object = data
}

object ImmutableMediaEventPolicy : IncomingRowPolicy {
    private val immutable = setOf(
        "event_id", "device_id", "kind", "resource_id", "logical_date", "position_ms",
        "duration_ms", "seconds", "observed_at", "data", "deleted_at",
    )
    override fun validate(operation: String, data: JsonValue.Object?) {
        require(operation != "DELETE") { "portable media event cannot be deleted" }
        if (data != null) require(data["deleted_at"] == null || data["deleted_at"] == JsonValue.Null)
    }
    override fun verify(sql: SqlOperations, operation: String, data: JsonValue.Object?, local: JsonValue.Object?) {
        if (local != null && data != null) {
            require(
                immutable.all { key ->
                    (data[key] ?: JsonValue.Null) == (local[key] ?: JsonValue.Null)
                },
            ) { "portable media event collision" }
        }
    }
}

object PluginDataPolicy : IncomingRowPolicy {
    override fun validate(operation: String, data: JsonValue.Object?) {
        if (operation == "DELETE" || data == null) return
        val fields = PluginIdentity(data)
        fields.validateIdentity()
        fields.validateVersions()
        JsonValue.parse(fields.text("data_json"))
        pluginUnknown(fields.text("unknown_json"))
    }

    override fun verify(sql: SqlOperations, operation: String, data: JsonValue.Object?, local: JsonValue.Object?) {
        if (local == null || data == null || operation == "DELETE") return
        val incoming = (data["schema_version"] as JsonValue.Number).asLongOrNull()!!
        val current = (local["schema_version"] as JsonValue.Number).asLongOrNull()!!
        require(incoming >= current) { "plugin data schema downgrade" }
    }
    override fun merge(data: JsonValue.Object, local: JsonValue.Object?): JsonValue.Object {
        val previous = (local?.get("unknown_json") as? JsonValue.Text)?.value?.let(::pluginUnknown)?.asMap().orEmpty()
        val incoming = pluginUnknown((data["unknown_json"] as JsonValue.Text).value).asMap()
        val merged = (previous + incoming).toMutableMap()
        val payload = JsonValue.parse((data["data_json"] as JsonValue.Text).value) as? JsonValue.Object
        payload?.asMap()?.forEach { (key, value) ->
            val envelope = merged[key] as? JsonValue.Object
            if (envelope != null && envelope["value"] != value) merged.remove(key)
        }
        return JsonValue.Object.from(data.asMap() + ("unknown_json" to JsonValue.text(JsonValue.Object.from(merged).canonical())))
    }
}

private fun pluginUnknown(text: String): JsonValue.Object {
    val unknown = JsonValue.parse(text) as? JsonValue.Object ?: error("invalid plugin unknown data")
    unknown.asMap().values.forEach { value ->
        val envelope = value as? JsonValue.Object ?: error("invalid plugin unknown envelope")
        require(envelope.containsKey("value") && envelope["hlc"] is JsonValue.Text && envelope["dev"] is JsonValue.Text)
        val seq = (envelope["seq"] as? JsonValue.Number)?.asLongOrNull()
        require(seq != null && seq in -9007199254740991L..9007199254740991L)
    }
    return unknown
}

private class PluginIdentity(private val data: JsonValue.Object) {
    fun text(key: String) = (data[key] as? JsonValue.Text)?.value ?: error("invalid plugin data")
    private fun number(key: String) = (data[key] as? JsonValue.Number)?.asLongOrNull() ?: error("invalid plugin data")

    fun validateIdentity() {
        val owner = text("owner_id")
        val kind = text("owner_kind")
        val namespace = text("namespace")
        val record = text("record_id")
        require(Regex("^(plugin|skill)\\.[A-Za-z0-9_.-]{1,121}$").matches(owner) && owner.startsWith("$kind."))
        require(Regex("^[A-Za-z0-9_.-]{1,128}$").matches(namespace) && ".." !in namespace)
        require(record.length in 1..512 && '\u0000' !in record)
        require(text("id") == JsonValue.array(listOf(JsonValue.text(owner), JsonValue.text(namespace), JsonValue.text(record))).literal())
        require(Regex("^[0-9a-f]{64}$").matches(text("source_hash")))
    }

    fun validateVersions() {
        val schema = number("schema_version")
        val minWriter = number("min_writer_schema")
        require(schema in 1..2147483647L && minWriter in 1..schema && number("local_version") > 0)
        require(text("package_version").length in 1..128 && text("device_id").length in 1..256)
        require(text("updated_at").isNotBlank())
    }
}
