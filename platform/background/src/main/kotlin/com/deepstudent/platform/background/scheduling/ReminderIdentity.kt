package com.deepstudent.platform.background.scheduling

/**
 * Stable alarm/notification identity: scheme + authority + schedule id + occurrence version.
 * Encoding is pure so JVM tests cover malformed and confused identities; no business field exists.
 */
internal object ReminderIdentity {
    private const val SCHEME = "dstu-reminder"
    private const val AUTHORITY = "alarm"
    private const val QUERY = "occurrence"

    fun encode(fire: ReminderFire): String = "$SCHEME://$AUTHORITY/${fire.scheduleId.value}?$QUERY=${fire.occurrence.value}"

    private val pattern = Regex("$SCHEME://$AUTHORITY/([^/?#]+)\\?$QUERY=([0-9]+)")

    fun decode(raw: String?): ReminderFire? {
        val (id, occurrence) = raw?.let(pattern::matchEntire)?.destructured ?: return null
        val scheduleId = ScheduleId.parseOrNull(id) ?: return null
        val number = occurrence.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return ReminderFire(scheduleId, OccurrenceNo.positive(number))
    }
}
