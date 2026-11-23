package com.deepstudent.app.scheduling

import com.deepstudent.core.common.json.JsonFields
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.background.scheduling.OccurrenceNo
import com.deepstudent.platform.background.scheduling.ScheduleId
import java.time.Instant

internal enum class SchedulePurpose { NOTE, REMINDER }

/** Local schedule definition; task content is never copied to WorkManager InputData or an Intent. */
internal data class ScheduledDraft(val title: String, val body: String, val purpose: SchedulePurpose) {
    init {
        require(title.isNotBlank() && title.length <= 200 && body.length <= 16_000) { "invalid local schedule details" }
    }
    override fun toString(): String = "ScheduledDraft($purpose)"
}

internal data class LocalSchedule(
    val id: ScheduleId,
    val draft: ScheduledDraft,
    val due: Instant,
    val periodMinutes: Int = 0,
    val lastHandled: Long = 0,
    val enabled: Boolean = true,
    val requiresIdle: Boolean = false,
    val activeOccurrence: Long = 0,
) {
    init {
        require(due.toEpochMilli() > 0 && lastHandled >= 0 && activeOccurrence >= 0)
        require(periodMinutes == 0 || (periodMinutes in 15..1440 && draft.purpose == SchedulePurpose.NOTE))
    }

    val occurrence: OccurrenceNo get() = OccurrenceNo(due.toEpochMilli())

    fun currentOccurrence(now: Instant): OccurrenceNo {
        if (periodMinutes == 0 || !now.isAfter(due)) return occurrence
        val period = periodMinutes * 60_000L
        val offset = (now.toEpochMilli() - occurrence.value) / period * period
        return OccurrenceNo(occurrence.value + offset)
    }

    fun handled(value: OccurrenceNo): LocalSchedule = copy(lastHandled = value.value, enabled = periodMinutes != 0, activeOccurrence = 0)
}

internal object ScheduleCodec {
    fun encode(row: LocalSchedule): String = JsonValue.Object.Builder()
        .put("title", row.draft.title).put("body", row.draft.body).put("purpose", row.draft.purpose.name)
        .put("due", row.due.toEpochMilli()).put("period", row.periodMinutes).put("handled", row.lastHandled)
        .put("enabled", row.enabled).put("idle", row.requiresIdle).put("active", row.activeOccurrence).build().literal()

    fun decode(id: ScheduleId, raw: String): LocalSchedule {
        val row = JsonValue.parseObject(raw)
        val draft = ScheduledDraft(
            JsonFields.string(row, "title"),
            JsonFields.string(row, "body"),
            SchedulePurpose.valueOf(JsonFields.string(row, "purpose")),
        )
        return LocalSchedule(
            id,
            draft,
            Instant.ofEpochMilli(row.number("due")),
            Math.toIntExact(row.number("period")),
            row.number("handled"),
            (row["enabled"] as JsonValue.Bool).value,
            (row["idle"] as JsonValue.Bool).value,
            row.number("active"),
        )
    }

    private fun JsonValue.Object.number(name: String) = requireNotNull((get(name) as? JsonValue.Number)?.asLongOrNull())
}
