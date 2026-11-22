package com.deepstudent.platform.database.internal

import com.deepstudent.platform.database.ProbeResult

/** A small, independently owned probe family. */
internal interface ProbeFamily {
    fun run(context: ProbeContext): List<ProbeResult>
}
