package com.deepstudent.platform.background.scheduling

/**
 * Opaque, stable identity of one platform schedule. The platform never interprets it as a task id,
 * todo id or business key; the host owns that mapping and all reminder data.
 */
@JvmInline
value class ScheduleId private constructor(val value: String) {
    companion object {
        private val FORMAT = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")

        fun parse(raw: String): ScheduleId {
            require(FORMAT.matches(raw)) {
                "invalid schedule id; expected 1..128 characters from [A-Za-z0-9_.:-]"
            }
            return ScheduleId(raw)
        }

        fun parseOrNull(raw: String?): ScheduleId? {
            if (raw == null || !FORMAT.matches(raw)) return null
            return ScheduleId(raw)
        }
    }
}

/**
 * Occurrence version chosen by the host. It only distinguishes generations of the same schedule.
 * [PERIODIC] is the periodic-tick sentinel; reminder callbacks and one-time work require a
 * positive occurrence so a stale occurrence can never be mistaken for a new one.
 */
@JvmInline
value class OccurrenceNo(val value: Long) {
    init {
        require(value >= 0) { "occurrence must not be negative" }
    }

    companion object {
        val PERIODIC = OccurrenceNo(0)

        fun positive(value: Long): OccurrenceNo {
            require(value > 0) { "one-time occurrence must be positive" }
            return OccurrenceNo(value)
        }
    }
}
