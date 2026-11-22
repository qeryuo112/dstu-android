package com.deepstudent.features.media.api

import com.deepstudent.core.common.json.JsonFields
import com.deepstudent.core.common.json.JsonValue

data class MediaEventKey(
    val eventId: String,
    val deviceId: String,
    val kind: MediaKind,
    val resourceId: String,
    val logicalDate: String,
)

data class MediaEventMeasurement(
    val positionMillis: Long?,
    val durationMillis: Long?,
    val seconds: Long?,
)

data class MediaEventPayload(
    val observedAt: MediaTimestamp,
    val data: JsonValue.Object,
)

data class MediaEventInput(
    val key: MediaEventKey,
    val measurement: MediaEventMeasurement,
    val payload: MediaEventPayload,
)

data class NormalizedMediaEvent(
    val key: MediaEventKey,
    val measurement: MediaEventMeasurement,
    val payload: MediaEventPayload,
) {
    val eventId: String get() = key.eventId
    val deviceId: String get() = key.deviceId
    val kind: MediaKind get() = key.kind
    val resourceId: String get() = key.resourceId
    val logicalDate: String get() = key.logicalDate
    val positionMillis: Long? get() = measurement.positionMillis
    val durationMillis: Long? get() = measurement.durationMillis
    val seconds: Long? get() = measurement.seconds
    val observedAt: MediaTimestamp get() = payload.observedAt
    val data: JsonValue.Object get() = payload.data
    val dataJson: String get() = data.canonical()
    val observedWire: String get() = observedAt.canonical

    fun matches(row: MediaEventRow): Boolean =
        key == row.key &&
            measurement == row.measurement &&
            observedAt.canonical == row.observedAt.canonical &&
            data.canonical() == row.data.canonical() &&
            row.transport.deletedAt == null
}

data class MediaEventTransport(
    val localVersion: Long,
    val updatedAt: MediaTimestamp,
    val deletedAt: String?,
) {
    init {
        if (localVersion <= 0) mediaError("INVALID_MEDIA_VERSION", "local_version must be positive")
    }
}

data class MediaEventRow(
    val key: MediaEventKey,
    val measurement: MediaEventMeasurement,
    val payload: MediaEventPayload,
    val transport: MediaEventTransport,
) {
    val eventId: String get() = key.eventId
    val deviceId: String get() = key.deviceId
    val kind: MediaKind get() = key.kind
    val resourceId: String get() = key.resourceId
    val logicalDate: String get() = key.logicalDate
    val positionMillis: Long? get() = measurement.positionMillis
    val durationMillis: Long? get() = measurement.durationMillis
    val seconds: Long? get() = measurement.seconds
    val observedAt: MediaTimestamp get() = payload.observedAt
    val data: JsonValue.Object get() = payload.data
    val localVersion: Long get() = transport.localVersion
    val updatedAt: MediaTimestamp get() = transport.updatedAt
    val deletedAt: String? get() = transport.deletedAt
    val dataJson: String get() = data.canonical()
}

enum class MediaAppendKind {
    INSERTED,
    DUPLICATE,
    COLLISION,
}

data class MediaAppendDecision(
    val kind: MediaAppendKind,
    val eventId: String,
)

object MediaEventNormalizer {
    private val stringFields = listOf("event_id", "device_id", "kind", "resource_id", "observed_at")

    fun decode(value: JsonValue.Object): NormalizedMediaEvent {
        val key = decodeKey(value)
        val observedAt = decodeObservedAt(value)
        val measurement = decodeMeasurement(value, key.kind)
        val data = decodeData(value, key)
        return NormalizedMediaEvent(
            key = key,
            measurement = measurement,
            payload = MediaEventPayload(observedAt, data),
        )
    }

    private fun decodeKey(value: JsonValue.Object): MediaEventKey {
        for (field in stringFields) {
            val item = value[field] as? JsonValue.Text
                ?: mediaError("INVALID_MEDIA_EVENT", "media event: $field must be a string")
            if (item.value.isEmpty() || item.value.length > 512) {
                mediaError("INVALID_MEDIA_EVENT", "media event: invalid $field")
            }
        }
        val eventId = (value["event_id"] as JsonValue.Text).value
        val deviceId = (value["device_id"] as JsonValue.Text).value
        val kind = MediaKind.fromWire((value["kind"] as JsonValue.Text).value)
        val resourceId = (value["resource_id"] as JsonValue.Text).value
        val logicalDate = value["logical_date"] as? JsonValue.Text
            ?: mediaError("INVALID_MEDIA_EVENT", "media event: logical_date must be a string")
        validLogicalDate(logicalDate.value)
        return MediaEventKey(eventId, deviceId, kind, resourceId, logicalDate.value)
    }

    private fun decodeObservedAt(value: JsonValue.Object): MediaTimestamp =
        MediaTimestamp.parse((value["observed_at"] as JsonValue.Text).value)

    private fun decodeMeasurement(value: JsonValue.Object, kind: MediaKind): MediaEventMeasurement {
        val seconds = optionalInteger(value, "seconds", 0, 86_400, kind == MediaKind.STUDY)
        val position = optionalInteger(
            value,
            "position_ms",
            0,
            MediaContract.SAFE_MILLISECOND_MAX,
            kind != MediaKind.STUDY,
        )
        val duration = optionalInteger(
            value,
            "duration_ms",
            0,
            MediaContract.SAFE_MILLISECOND_MAX,
            kind != MediaKind.STUDY,
        )
        validateKindFields(kind, seconds, position, duration)
        return MediaEventMeasurement(position, duration, seconds)
    }

    private fun decodeData(value: JsonValue.Object, key: MediaEventKey): JsonValue.Object {
        val data = when (val item = value["data"]) {
            null, JsonValue.Null -> JsonValue.Object.EMPTY
            is JsonValue.Object -> item
            else -> mediaError("INVALID_MEDIA_DATA", "media event data must be an object")
        }
        if (key.kind == MediaKind.SUBTITLE) validateSubtitleData(key.eventId, data)
        return data
    }

    fun decode(text: String): NormalizedMediaEvent =
        decode(JsonValue.parseObject(text, "media event"))

    fun classify(existing: MediaEventRow?, event: NormalizedMediaEvent): MediaAppendDecision =
        when {
            existing == null -> MediaAppendDecision(MediaAppendKind.INSERTED, event.eventId)
            event.matches(existing) -> MediaAppendDecision(MediaAppendKind.DUPLICATE, event.eventId)
            else -> MediaAppendDecision(MediaAppendKind.COLLISION, event.eventId)
        }

    fun toRow(
        event: NormalizedMediaEvent,
        localVersion: Long = 1,
        updatedAt: MediaTimestamp = event.observedAt,
        deletedAt: String? = null,
    ): MediaEventRow = MediaEventRow(
        key = event.key,
        measurement = event.measurement,
        payload = event.payload,
        transport = MediaEventTransport(localVersion, updatedAt, deletedAt),
    )

    private fun optionalInteger(
        value: JsonValue.Object,
        field: String,
        minimum: Long,
        maximum: Long,
        required: Boolean,
    ): Long? {
        val item = value[field]
        if (item == null || item == JsonValue.Null) {
            if (required) mediaError("MISSING_MEDIA_FIELD", "media event $field is required")
            return null
        }
        val number = item as? JsonValue.Number
            ?: mediaError("INVALID_MEDIA_INTEGER", "media event $field must be an integer")
        return mediaInteger(number.literal, field, minimum, maximum)
    }

    private fun validateKindFields(
        kind: MediaKind,
        seconds: Long?,
        position: Long?,
        duration: Long?,
    ) {
        when (kind) {
            MediaKind.STUDY -> validateStudy(position, duration)
            MediaKind.RESUME -> validateResume(seconds, position, duration)
            MediaKind.SUBTITLE -> validateSubtitleRange(seconds, position, duration)
        }
    }

    private fun validateStudy(position: Long?, duration: Long?) {
        if (position != null) mediaError("INVALID_MEDIA_EVENT", "study event cannot carry position_ms")
        if (duration != null) mediaError("INVALID_MEDIA_EVENT", "study event cannot carry duration_ms")
    }

    private fun validateResume(seconds: Long?, position: Long?, duration: Long?) {
        if (seconds != null) mediaError("INVALID_MEDIA_EVENT", "resume event cannot carry seconds")
        if (duration != 0L && position!! > duration!!) {
            mediaError("INVALID_MEDIA_RANGE", "resume position exceeds duration")
        }
    }

    private fun validateSubtitleRange(seconds: Long?, position: Long?, duration: Long?) {
        if (seconds != null) mediaError("INVALID_MEDIA_EVENT", "subtitle event cannot carry seconds")
        if (position!! > duration!!) {
            mediaError("INVALID_MEDIA_RANGE", "subtitle range is inverted")
        }
    }

    private fun validateSubtitleData(eventId: String, data: JsonValue.Object) {
        for (field in listOf("plan_id", "version", "index", "text")) {
            if (!data.containsKey(field)) {
                mediaError("INVALID_SUBTITLE", "subtitle event missing data.$field")
            }
        }
        val planId = data["plan_id"] as? JsonValue.Text
            ?: mediaError("INVALID_SUBTITLE", "subtitle plan_id")
        if (planId.value.isEmpty()) mediaError("INVALID_SUBTITLE", "subtitle plan_id")
        val version = data["version"] as? JsonValue.Number
            ?: mediaError("INVALID_SUBTITLE", "subtitle version must be an integer")
        val index = data["index"] as? JsonValue.Number
            ?: mediaError("INVALID_SUBTITLE", "subtitle index must be an integer")
        mediaInteger(version.literal, "version", 1, MediaContract.SAFE_MILLISECOND_MAX)
        mediaInteger(index.literal, "index", 0, MediaContract.SAFE_MILLISECOND_MAX)
        if (data["text"] !is JsonValue.Text) {
            mediaError("INVALID_SUBTITLE", "subtitle text")
        }
        if (eventId.isEmpty()) mediaError("INVALID_SUBTITLE", "subtitle event_id")
    }
}

data class MediaRowInput(
    val raw: JsonValue.Object,
) {
    fun decodeRow(): MediaEventRow {
        val event = MediaEventNormalizer.decode(raw)
        val localVersion = JsonFields.integer(raw, "local_version")
        if (localVersion <= 0) mediaError("INVALID_MEDIA_VERSION", "local_version must be positive")
        val updatedAt = JsonFields.string(raw, "updated_at")
        return MediaEventRow(
            key = event.key,
            measurement = event.measurement,
            payload = event.payload,
            transport = MediaEventTransport(
                localVersion = localVersion,
                updatedAt = MediaTimestamp.parse(updatedAt, "updated_at"),
                deletedAt = JsonFields.optionalString(raw, "deleted_at"),
            ),
        )
    }
}
