package com.deepstudent.features.environments.backend

import com.deepstudent.core.common.json.JsonFields
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.environments.api.EnvironmentFailure
import com.deepstudent.features.environments.api.EnvironmentFailureCode
import com.deepstudent.features.environments.api.InstalledVersion
import com.deepstudent.features.environments.api.PackageReceipt
import java.time.Instant

object EnvironmentJournalCodec {
    private const val FORMAT_VERSION = 1L

    fun encode(journal: EnvironmentJournal): String = JsonValue.Object.Builder()
        .put("formatVersion", FORMAT_VERSION)
        .put("rootfsId", journal.rootfsId ?: "")
        .put("rootfsSha256", journal.rootfsSha256 ?: "")
        .put("generationId", journal.generationId ?: "")
        .put("checkpoint", journal.checkpoint.name)
        .put("activeGeneration", journal.activeGeneration ?: "")
        .put("pendingRecipeId", journal.pendingRecipeId ?: "")
        .put("lastTaskId", journal.lastTaskId ?: "")
        .put("updatedAt", journal.updatedAt.toString())
        .put("failure", journal.failure?.let(::failureJson) ?: JsonValue.Null)
        .put("receipts", receiptsJson(journal.receipts))
        .build()
        .canonical()

    private fun receiptsJson(receipts: Map<String, PackageReceipt>): JsonValue {
        val builder = JsonValue.Object.Builder()
        receipts.toSortedMap().forEach { (id, receipt) -> builder.put(id, receiptJson(receipt)) }
        return builder.build()
    }

    private fun receiptJson(receipt: PackageReceipt): JsonValue = JsonValue.Object.Builder()
        .put("recipeId", receipt.recipeId)
        .put("installedAt", receipt.installedAt)
        .put("versions", JsonValue.Array(receipt.versions.map(::versionJson)))
        .build()

    private fun versionJson(version: InstalledVersion): JsonValue = JsonValue.Object.Builder()
        .put("name", version.name)
        .put("version", version.version)
        .put("source", version.source)
        .build()

    private fun failureJson(failure: EnvironmentFailure): JsonValue = JsonValue.Object.Builder()
        .put("code", failure.code.name)
        .put("message", failure.message)
        .put("recoverable", failure.recoverable)
        .build()

    fun decode(text: String): EnvironmentJournal {
        val root = JsonValue.parseObject(text, "environment journal")
        require(JsonFields.integer(root, "formatVersion") == FORMAT_VERSION) {
            "unsupported environment journal version"
        }
        return EnvironmentJournal(
            identity = EnvironmentJournalIdentity(
                rootfsId = optional(root, "rootfsId"),
                rootfsSha256 = optional(root, "rootfsSha256"),
                generationId = optional(root, "generationId"),
            ),
            checkpoint = EnvironmentCheckpoint.valueOf(JsonFields.string(root, "checkpoint")),
            activeGeneration = optional(root, "activeGeneration"),
            receipts = decodeReceipts(JsonFields.objectValue(root, "receipts")),
            pendingRecipeId = optional(root, "pendingRecipeId"),
            lastTaskId = optional(root, "lastTaskId"),
            failure = decodeOptionalFailure(root),
            updatedAt = Instant.parse(JsonFields.string(root, "updatedAt")),
        ).also(EnvironmentJournalValidation::requireValid)
    }

    private fun decodeOptionalFailure(root: JsonValue.Object): EnvironmentFailure? = when (val value = root["failure"]) {
        JsonValue.Null -> null
        is JsonValue.Object -> decodeFailure(value)
        else -> error("invalid environment failure")
    }

    private fun decodeFailure(value: JsonValue.Object): EnvironmentFailure = EnvironmentFailure(
        code = EnvironmentFailureCode.valueOf(JsonFields.string(value, "code")),
        message = JsonFields.string(value, "message"),
        recoverable = JsonFields.boolean(value, "recoverable"),
    )

    private fun decodeReceipts(value: JsonValue.Object): Map<String, PackageReceipt> =
        value.keys().associate { key ->
            val receipt = value[key] as? JsonValue.Object ?: error("invalid package receipt")
            key to decodeReceipt(receipt).also { require(it.recipeId == key) { "receipt key mismatch" } }
        }

    private fun decodeReceipt(value: JsonValue.Object): PackageReceipt {
        val versions = (value["versions"] as? JsonValue.Array ?: error("invalid installed versions")).values.map { item ->
            val row = item as? JsonValue.Object ?: error("invalid installed version")
            InstalledVersion(
                JsonFields.string(row, "name"),
                JsonFields.string(row, "version"),
                JsonFields.string(row, "source"),
            )
        }
        return PackageReceipt(
            recipeId = JsonFields.string(value, "recipeId"),
            installedAt = JsonFields.string(value, "installedAt"),
            versions = versions,
        )
    }

    private fun optional(value: JsonValue.Object, key: String): String? =
        JsonFields.optionalString(value, key)?.takeIf { it.isNotEmpty() }
}
