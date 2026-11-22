package com.deepstudent.features.environments.ui

import java.util.logging.Logger

/** Only bounded event names, task state and error codes; never document URIs or raw exceptions. */
internal object EnvironmentUiLog {
    private val logger = Logger.getLogger("DstuEnvironmentUi")
    fun event(event: String, fields: String = "") {
        logger.info("event=$event $fields")
    }
}
