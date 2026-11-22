package com.deepstudent.features.terminal.backend

import java.io.InputStream
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Reads complete UTF-8 code points. The reader is intentionally not closed here because the PTY
 * input and output descriptors are owned together and closed by the process port.
 */
internal class TerminalIoPump(
    private val chunkSize: Int = 4096,
) {
    suspend fun pump(input: InputStream, accept: (String) -> Unit) = withContext(Dispatchers.IO) {
        val reader = InputStreamReader(input, Charsets.UTF_8)
        val buffer = CharArray(chunkSize)
        while (currentCoroutineContext().isActive) {
            val count = reader.read(buffer)
            if (count < 0) return@withContext
            if (count > 0) accept(String(buffer, 0, count))
        }
    }
}
