package com.deepstudent.services.backup.backend

import java.io.File
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal object ContinuityFixture {
    private fun locate(name: String): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, "fixtures/legacy-mobile/$name")
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("fixture not found")
    }

    val bytes: ByteArray by lazy {
        locate("legacy-mobile-continuity-continuity-v1.continuity.json").readBytes().also {
            assertEquals("9c6b0fa1c3b93b8677514e6add0f0aecec4521e4ee4289cbe2d1f38a09bcadb6", digest(it))
        }
    }
    val root: JsonObject get() = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject
    val trust: ContinuityTrust by lazy {
        val zip = locate("legacy-mobile-continuity-continuity-v1.zip")
        assertEquals(3987749L, zip.length())
        val sha = digest(zip.readBytes())
        assertEquals("774b1aea192eaec9f991c127aa275689b624b6679724e6ac6dea1316f5a13924", sha)
        val manifest = ZipFile(zip).use { archive ->
            archive.getInputStream(archive.getEntry("manifest.json")).use { ManifestCodec.decode(it.readBytes().toString(Charsets.UTF_8)) }
        }
        ContinuityTrust(
            ContinuityArchiveEvidence(sha, zip.length(), manifest),
            ContinuitySourceInstallation("com.deepstudent.app", "0.20.0", 1114586, "installation-legacy-mobile-demo"),
            ContinuityRepository("default", "local", "file:///deep-student/local/sync"),
            ContinuityWriterConfirmation("device-7f3a91c2", stopped = true, unchangedSinceSnapshot = true),
            digest(bytes),
        )
    }
    fun input(root: JsonObject) = root.toString().toByteArray(Charsets.UTF_8)
    fun testTrust(bytes: ByteArray) = trust.copy(verifiedSidecarSha256 = digest(bytes))
    fun validate(root: JsonObject): ContinuityValidation {
        val bytes = input(root)
        return ContinuityValidator.validate(bytes, testTrust(bytes))
    }
    fun validateRaw(raw: String): ContinuityValidation {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        return ContinuityValidator.validate(bytes, testTrust(bytes))
    }
    fun rejected(root: JsonObject, reason: ContinuityFailure? = null) = rejected(validate(root), reason)
    fun rejected(result: ContinuityValidation, reason: ContinuityFailure? = null) {
        assertTrue("Expected rejection but got $result", result is ContinuityValidation.Rejected)
        if (reason != null) assertEquals(reason, (result as ContinuityValidation.Rejected).reason)
    }
}

internal fun JsonObject.change(key: String, value: JsonElement): JsonObject = JsonObject(toMutableMap().also { it[key] = value })
internal fun JsonObject.removeKey(key: String): JsonObject = JsonObject(toMutableMap().also { it.remove(key) })
internal fun JsonObject.changeObject(key: String, change: (JsonObject) -> JsonObject) = change(key, change(getValue(key) as JsonObject))
internal fun JsonObject.changeState(table: String, change: (JsonArray) -> JsonArray): JsonObject =
    changeObject("state") { state -> state.change(table, change(state.getValue(table) as JsonArray)) }
internal fun JsonArray.changeRow(index: Int, change: (JsonObject) -> JsonObject): JsonArray =
    JsonArray(mapIndexed { i, value -> if (i == index) change(value as JsonObject) else value })
