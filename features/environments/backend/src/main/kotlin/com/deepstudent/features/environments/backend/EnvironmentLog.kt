package com.deepstudent.features.environments.backend

import java.util.logging.Level
import java.util.logging.Logger

internal object EnvironmentLog {
    private val logger = Logger.getLogger("DstuEnvironments")

    fun event(event: String, fields: String = "") {
        logger.log(Level.INFO, "event=${safe(event)}${safe(fields).takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()}")
    }

    fun failure(event: String, error: Throwable, fields: String = "") {
        val suffix = safe(fields).takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        logger.log(Level.WARNING, "event=${safe(event)}$suffix type=${error.javaClass.simpleName}")
    }
    private fun safe(value: String): String = value.take(512).map {
        if (it.isLetterOrDigit() || it in " =._:/-") it else '_'
    }.joinToString("")
}
