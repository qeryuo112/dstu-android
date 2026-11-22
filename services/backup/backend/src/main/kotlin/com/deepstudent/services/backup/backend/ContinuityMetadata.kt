package com.deepstudent.services.backup.backend

import kotlinx.serialization.json.JsonObject

internal fun validateContinuityFormat(root: JsonObject) {
    val format = root.text("format")
    val version = root.integer("formatVersion", allowString = false)
    val status = root.text("status")
    continuityRequire(format == "dstu.legacy.mobile.continuity" && version == 1L && status == "new-contract-in-this-unit", ContinuityFailure.UNSUPPORTED_FORMAT)
}

internal fun validateContinuityMetadata(root: JsonObject) {
    root.optionalText("note")
    root.optionalText("created_at")
    if (root.containsKey("generator")) {
        val generator = root.objectValue("generator")
        generator.shape(setOf("tool", "unit", "runtime", "seed"))
        generator.keys.forEach(generator::text)
    }
    val policy = root.objectValue("key_policy")
    policy.shape(setOf("material_in_archive", "manifest"), setOf("explanation"))
    continuityRequire(!policy.bool("material_in_archive") && policy.text("manifest") == "not_present")
    policy.optionalText("explanation")
    validateContinuityInstallationMetadata(root.objectValue("source_installation"))
}

private fun validateContinuityInstallationMetadata(source: JsonObject) {
    source.shape(
        setOf("applicationId", "versionName", "versionCode", "installationId"),
        setOf("appName", "identitySource", "artifact", "packageNote"),
    )
    listOf("appName", "artifact", "packageNote").forEach(source::optionalText)
    if (!source.containsKey("identitySource")) return
    val origin = source.objectValue("identitySource")
    origin.shape(setOf("file", "sha256", "lines"))
    origin.text("file")
    continuityRequire(Regex("[0-9a-f]{64}").matches(origin.text("sha256")))
    val lines = origin.objectValue("lines")
    lines.shape(setOf("applicationId", "appName", "versionName", "versionCode"))
    lines.keys.forEach { lines.integer(it, allowString = false, positive = true) }
}
