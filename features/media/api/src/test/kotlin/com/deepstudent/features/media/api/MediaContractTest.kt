package com.deepstudent.features.media.api

import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.json.JsonFields
import com.deepstudent.core.common.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaContractTest {
    private val fixture = RepoFixtures.json("fixtures/media-time.json")
    private val events = (fixture["events"] as JsonValue.Array).values.map { it as JsonValue.Object }

    @Test
    fun fixtureEventsNormalizeWithCanonicalMutableFields() {
        val rows = events.map { MediaEventNormalizer.toRow(MediaEventNormalizer.decode(it)) }
        assertEquals(6, rows.size)
        assertEquals("2026-11-15T08:02:00Z", rows.first { it.eventId == "s022-resume-b" }.observedAt.canonical)
        assertEquals(12000L, rows.first { it.eventId == "s022-resume-b" }.positionMillis)
        assertEquals(90L, rows.first { it.eventId == "s022-study-a" }.seconds)
        assertEquals("{}", rows.first { it.eventId == "s022-resume-a" }.data.canonical())
    }

    @Test
    fun collisionClassificationMatchesAdapterRule() {
        val studyB = events.first { JsonFields.string(it, "event_id") == "s022-study-b" }
        val row = MediaEventNormalizer.toRow(MediaEventNormalizer.decode(studyB))
        val same = MediaEventNormalizer.decode(studyB)
        assertEquals(MediaAppendKind.DUPLICATE, MediaEventNormalizer.classify(row, same).kind)

        val changed = replace(studyB, "seconds", JsonValue.integer(31))
        val changedEvent = MediaEventNormalizer.decode(changed)
        assertEquals(MediaAppendKind.COLLISION, MediaEventNormalizer.classify(row, changedEvent).kind)
        assertEquals(
            MediaAppendKind.INSERTED,
            MediaEventNormalizer.classify(null, changedEvent).kind,
        )
    }

    @Test
    fun observedTimestampUsesUtcSecondsPrecisionAndOffsetNormalization() {
        assertEquals(
            "2026-11-15T08:00:00Z",
            MediaTimestamp.parse("2026-11-15T16:00:00+08:00").canonical,
        )
        assertEquals(
            "2026-11-15T08:00:00Z",
            MediaTimestamp.parse("2026-11-15T08:00:00.000Z").canonical,
        )
        assertEquals(
            "2026-11-15T08:00:00.123Z",
            MediaTimestamp.parse("2026-11-15T16:00:00.123+08:00").canonical,
        )
        assertThrows(DstuException::class.java) {
            MediaTimestamp.parse("2026-02-30T08:00:00Z")
        }
    }

    @Test
    fun negativeAndBoundaryInputsAreRejected() {
        assertFailure(study(seconds = "-1"), "MEDIA_INTEGER_RANGE")
        assertFailure(study(seconds = "86401"), "MEDIA_INTEGER_RANGE")
        assertFailure(study(logicalDate = "2026-11-31"), "INVALID_LOGICAL_DATE")
        assertFailure(study(logicalDate = "2026-02-30"), "INVALID_LOGICAL_DATE")
        assertFailure(resume(position = "-1"), "MEDIA_INTEGER_RANGE")
        assertFailure(resume(position = "101", duration = "100"), "INVALID_MEDIA_RANGE")
        assertFailure(subtitle(position = "2000", duration = "1000"), "INVALID_MEDIA_RANGE")
        assertFailure(subtitle(version = "\"two\""), "INVALID_SUBTITLE")
        assertFailure(subtitle(omitText = true), "INVALID_SUBTITLE")
        assertFailure(study(observedAt = "2026-11-15T08:00:00"), "INVALID_MEDIA_TIME")
        assertFailure(study(observedAt = "not-a-time"), "INVALID_MEDIA_TIME")
        assertFailure(resume(duration = "9007199254740992"), "MEDIA_INTEGER_RANGE")
        assertFailure(resume(duration = "9223372036854775807"), "MEDIA_INTEGER_RANGE")
        assertFailure(study(position = "0"), "INVALID_MEDIA_EVENT")
        assertFailure(study(data = "[]"), "INVALID_MEDIA_DATA")
        assertFailure(resume(position = null), "MISSING_MEDIA_FIELD")
    }

    @Test
    fun missingDataDefaultsToEmptyObjectLikeDesktopAdapter() {
        val normalized = MediaEventNormalizer.decode(study(omitData = true))
        assertEquals("{}", normalized.data.canonical())
        assertEquals("2026-11-15T08:00:00Z", normalized.observedWire)
    }

    private fun assertFailure(json: String, code: String) {
        val failure = assertThrows(DstuException::class.java) {
            MediaEventNormalizer.decode(json)
        }
        assertEquals(code, failure.error.code)
    }

    private fun study(
        seconds: String = "1",
        observedAt: String = "2026-11-15T08:00:00Z",
        logicalDate: String = "2026-11-15",
        position: String? = null,
        data: String = "{}",
        omitData: Boolean = false,
    ): String {
        val fields = mutableListOf(
            "\"event_id\":\"negative-study\"",
            "\"device_id\":\"device-a\"",
            "\"kind\":\"study\"",
            "\"resource_id\":\"public-media-resource\"",
            "\"logical_date\":\"$logicalDate\"",
            "\"seconds\":$seconds",
            "\"observed_at\":\"$observedAt\"",
        )
        if (position != null) fields += "\"position_ms\":$position"
        if (!omitData) fields += "\"data\":$data"
        return "{${fields.joinToString(",")}}"
    }

    private fun resume(
        position: String? = "1000",
        duration: String = "1000",
    ): String {
        val positionField = if (position == null) "" else "\"position_ms\":$position,"
        return """
            {"event_id":"negative-resume","device_id":"device-a","kind":"resume",
             "resource_id":"public-media-resource","logical_date":"2026-11-15",
             ${positionField}"duration_ms":$duration,"observed_at":"2026-11-15T08:00:00Z","data":{}}
        """.trimIndent()
    }

    private fun subtitle(
        position: String = "1000",
        duration: String = "2000",
        version: String = "1",
        omitText: Boolean = false,
    ): String {
        val text = if (omitText) "" else ",\"text\":\"cue\""
        return """
            {"event_id":"negative-subtitle","device_id":"device-a","kind":"subtitle",
             "resource_id":"public-media-resource","logical_date":"2026-11-15",
             "position_ms":$position,"duration_ms":$duration,
             "observed_at":"2026-11-15T08:00:00Z",
             "data":{"plan_id":"plan","version":$version,"index":0$text}}
        """.trimIndent()
    }

    private fun replace(base: JsonValue.Object, key: String, value: JsonValue): JsonValue.Object {
        val builder = JsonValue.Object.Builder()
        for (field in base.keys()) builder.put(field, if (field == key) value else base[field] ?: JsonValue.Null)
        return builder.build()
    }
}
