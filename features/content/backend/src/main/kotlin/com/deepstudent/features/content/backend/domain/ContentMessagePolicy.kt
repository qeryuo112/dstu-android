package com.deepstudent.features.content.backend.domain

import com.deepstudent.features.content.api.ContentEvent
import com.deepstudent.features.content.api.ContentMessageValidator
import com.deepstudent.core.common.json.JsonValue

class ContentMessagePolicy(
    private val origin: String,
    private val nonce: String,
    private val limit: Int = 100,
    private val maxBytes: Int = 4096,
) : ContentMessageValidator {
    private var remaining = limit
    private var active = true

    @Synchronized
    override fun accept(sourceOrigin: String, mainFrame: Boolean, payload: String): ContentEvent? {
        if (!permitted(sourceOrigin, mainFrame)) return null
        remaining--
        if (payload.length > maxBytes || payload.toByteArray(Charsets.UTF_8).size > maxBytes) return null
        val message = runCatching { JsonValue.parse(payload) as? JsonValue.Array }.getOrNull() ?: return null
        if (message.values.size != 3 || string(message, 0) != nonce) return null
        val type = string(message, 1) ?: return null
        val value = string(message, 2) ?: return null
        return event(type, value)
    }

    @Synchronized override fun close() { active = false; remaining = 0 }

    private fun string(message: JsonValue.Array, index: Int): String? =
        (message.values[index] as? JsonValue.Text)?.value

    private fun permitted(sourceOrigin: String, mainFrame: Boolean): Boolean =
        active && remaining > 0 && sourceOrigin == origin && mainFrame

    private fun event(type: String, value: String): ContentEvent? = when (type) {
        "answer" -> if (value.length <= 1024) ContentEvent.Answer(value) else null
        "openLink" -> if (ContentResourcePolicy.isSafeLink(value)) ContentEvent.OpenLink(value) else null
        "replay" -> if (value.isEmpty()) ContentEvent.Replay else null
        else -> null
    }
}
