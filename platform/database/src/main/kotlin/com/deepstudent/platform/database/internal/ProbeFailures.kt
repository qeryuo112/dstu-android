package com.deepstudent.platform.database.internal

import com.deepstudent.platform.database.DatabaseError

/** Internal carrier for an already classified driver failure. */
internal class DatabaseFailure(
    val error: DatabaseError,
) : RuntimeException(error.message)

/** A capability is absent from this SQLite build. */
internal class ProbeUnsupported(
    val reason: String,
) : RuntimeException(reason)

/** A capability exists but behaved differently than the frozen expectation. */
internal class ProbeAssertionFailure(
    val label: String,
) : RuntimeException(label)
