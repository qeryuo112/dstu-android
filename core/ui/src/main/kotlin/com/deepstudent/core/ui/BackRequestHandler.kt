package com.deepstudent.core.ui

/** Returns false when the host should handle back at the root destination. */
fun interface BackRequestHandler {
    fun onBackRequested(): Boolean
}
