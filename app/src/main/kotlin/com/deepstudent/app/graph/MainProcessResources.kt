package com.deepstudent.app.graph

/**
 * Process-wide resource owner placeholder for the main process.
 *
 * S1.02 intentionally registers no database, runtime service or task owner.
 * Accessing the main owner therefore fails explicitly until a later unit
 * supplies the real implementation through app assembly.
 */
internal interface MainProcessResources : AutoCloseable {
    val description: String
}

internal fun interface MainResourceFactory {
    fun open(): MainProcessResources
}
