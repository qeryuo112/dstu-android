package com.deepstudent.app.graph

/**
 * Pure, testable formatter shared by graph logging.
 *
 * Throwables are reduced to their fixed class name. The original message,
 * toString(), cause and stack trace are never read or emitted.
 */
internal object GraphLogFormatter {
    fun format(event: String, fields: Map<String, Any?> = emptyMap()): String {
        if (fields.isEmpty()) return event
        return buildString {
            append(event)
            fields.toSortedMap().forEach { (key, value) ->
                append(' ')
                append(key)
                append('=')
                append(value)
            }
        }
    }

    fun formatWarning(
        event: String,
        throwable: Throwable?,
        fields: Map<String, Any?> = emptyMap(),
    ): String {
        if (throwable == null) return format(event, fields)
        val safeFields = fields + (ERROR_CLASS_FIELD to throwable.javaClass.name)
        return format(event, safeFields)
    }

    private const val ERROR_CLASS_FIELD = "errorClass"
}
