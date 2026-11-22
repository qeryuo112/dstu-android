package com.deepstudent.features.terminal.backend

import com.deepstudent.features.terminal.api.TerminalSessionId
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Structured events contain only lifecycle data. Terminal output, commands and user input must
 * never be placed in these arguments.
 */
internal object TerminalLog {
    private val logger = Logger.getLogger("DstuTerminal")

    fun event(event: String, id: TerminalSessionId? = null, code: Int? = null) {
        val session = id?.let { " session=${it.value}" }.orEmpty()
        val exit = code?.let { " code=$it" }.orEmpty()
        logger.log(Level.INFO, "event=$event$session$exit")
    }

    fun failure(event: String, id: TerminalSessionId?, error: Throwable) {
        val session = id?.let { " session=${it.value}" }.orEmpty()
        logger.log(Level.WARNING, "event=$event$session type=${error.javaClass.simpleName}")
    }
}
