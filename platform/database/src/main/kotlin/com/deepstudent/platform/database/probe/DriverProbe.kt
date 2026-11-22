package com.deepstudent.platform.database.probe

import com.deepstudent.platform.database.ProbeId
import com.deepstudent.platform.database.ProbeResult
import com.deepstudent.platform.database.internal.ProbeContext
import com.deepstudent.platform.database.internal.ProbeFamily
import com.deepstudent.platform.database.internal.expectTrue

internal class DriverProbe : ProbeFamily {
    override fun run(context: ProbeContext): List<ProbeResult> = listOf(
        context.capture(ProbeId.DRIVER_IDENTITY) {
            val implementation = context.factory.implementationClass
            expectTrue(
                implementation == BUNDLED_DRIVER_CLASS,
                "driver must be the AndroidX bundled driver, actual <$implementation>",
            )
            mapOf(
                "coordinate" to context.factory.coordinate,
                "implementationClass" to implementation,
                "hasConnectionPool" to context.factory.hasConnectionPool.toString(),
            )
        },
    )

    private companion object {
        const val BUNDLED_DRIVER_CLASS = "androidx.sqlite.driver.bundled.BundledSQLiteDriver"
    }
}
