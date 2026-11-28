package com.deepstudent.services.providers.data

import com.deepstudent.platform.network.SseEnd
import com.deepstudent.platform.network.SseEvent
import com.deepstudent.services.providers.api.ProviderException
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderStreamEvent
import com.deepstudent.services.providers.api.ProviderStreamResult

/** Shared frame helpers for the Responses decoder unit tests. */
internal fun responsesFrame(data: String, type: String = "") = SseEvent(data, type, "")

/** Runs the frames through a fresh decoder and keeps a clean terminal result when there is one. */
internal fun responsesDecode(
    vararg frames: SseEvent,
    moderated: Boolean = false,
    webTools: Boolean = false,
): Pair<List<ProviderStreamEvent>, ProviderStreamResult?> {
    val decoder = OpenAiResponsesDecoder(moderated, webTools)
    val events = frames.flatMap { decoder.accept(it) }
    return events to runCatching { decoder.finish(SseEnd.COMPLETE) }.getOrNull()
}

/** Runs the frames through a fresh decoder and reports the fixed failure its end produces. */
internal fun responsesFailureCode(vararg frames: SseEvent, moderated: Boolean = false, webTools: Boolean = false): ProviderFailureCode {
    val decoder = OpenAiResponsesDecoder(moderated, webTools)
    frames.forEach { decoder.accept(it) }
    try {
        decoder.finish(SseEnd.COMPLETE)
    } catch (failure: ProviderException) {
        return failure.failure.code
    }
    error("expected a fixed failure but the stream ended cleanly")
}
