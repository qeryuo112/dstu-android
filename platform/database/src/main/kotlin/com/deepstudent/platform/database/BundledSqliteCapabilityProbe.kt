package com.deepstudent.platform.database

import com.deepstudent.platform.database.internal.ProbeRunner
import java.io.File

/**
 * Runs the S1.04 probe matrix with `androidx.sqlite:sqlite-bundled`.
 *
 * [workDirectory] must be writable. The probe creates a private temporary
 * directory below it, closes every connection/statement, and removes that
 * directory before returning.
 */
public class BundledSqliteCapabilityProbe(
    private val workDirectory: File,
) : DatabaseCapabilityProbe {

    override fun probe(
        errorSink: DatabaseErrorSink,
        log: DatabaseProbeLog,
    ): DatabaseProbeReport = ProbeRunner().run(workDirectory, errorSink, log)
}
