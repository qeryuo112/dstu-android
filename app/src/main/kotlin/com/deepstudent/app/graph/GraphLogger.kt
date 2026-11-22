package com.deepstudent.app.graph

import android.util.Log

/**
 * Structured logging seam for graph lifecycle tests.
 *
 * [warn] accepts a Throwable for classification only. Implementations must not
 * emit the throwable message, toString(), cause or stack trace because close
 * failures may contain credentials or user content.
 */
interface GraphLogger {
    fun info(event: String, fields: Map<String, Any?> = emptyMap())

    fun warn(
        event: String,
        throwable: Throwable? = null,
        fields: Map<String, Any?> = emptyMap(),
    )
}

object AndroidGraphLogger : GraphLogger {
    private const val TAG = "DstuGraph"

    override fun info(event: String, fields: Map<String, Any?>) {
        Log.i(TAG, GraphLogFormatter.format(event, fields))
    }

    override fun warn(event: String, throwable: Throwable?, fields: Map<String, Any?>) {
        Log.w(TAG, GraphLogFormatter.formatWarning(event, throwable, fields))
    }
}
