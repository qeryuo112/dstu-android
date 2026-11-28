package com.deepstudent.features.chat.api

import java.util.Collections

/** An owned, immutable conversation view captured from explicit input. */
class TurnSnapshot private constructor(val conversation: ChatConversation) {
    override fun equals(other: Any?): Boolean = other is TurnSnapshot && conversation == other.conversation
    override fun hashCode(): Int = conversation.hashCode()
    override fun toString(): String = "TurnSnapshot(redacted)"

    companion object {
        /** Captures the supplied values in their existing order. */
        fun freeze(source: ChatConversation): TurnSnapshot {
            val details = source.session.details
            val session = source.session.copy(details = details.copy(clear = ownedSet(details.clear)))
            val messages = source.messages.map {
                it.copy(blockIds = ownedList(it.blockIds), payload = it.payload.copy(clear = ownedSet(it.payload.clear)))
            }
            val blocks = source.blocks.map {
                it.copy(details = it.details.copy(clear = ownedSet(it.details.clear)))
            }
            // JsonValue containers defensively own their children and expose unmodifiable collections.
            return TurnSnapshot(ChatConversation(session, ownedList(messages), ownedList(blocks)))
        }
    }
}

private fun <T> ownedList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
private fun <T> ownedSet(values: Set<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
