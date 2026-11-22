package com.deepstudent.features.environments.data

import java.util.logging.Level
import java.util.logging.Logger

internal object EnvironmentDataLog {
    private val logger = Logger.getLogger("DstuEnvironmentData")

    fun event(event: String, fields: String = "") {
        logger.log(Level.INFO, "event=$event${fields.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()}")
    }

    fun failure(event: String, error: Throwable, fields: String = "") {
        val suffix = fields.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        logger.log(Level.WARNING, "event=$event$suffix type=${error.javaClass.simpleName}")
    }
}
