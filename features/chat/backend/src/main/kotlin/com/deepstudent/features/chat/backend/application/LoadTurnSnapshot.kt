package com.deepstudent.features.chat.backend.application

import com.deepstudent.features.chat.api.ChatRepository
import com.deepstudent.features.chat.api.TurnSnapshot

/** The backend consumes the same chat port as persistence; preparation remains a separate concern. */
class LoadTurnSnapshot(private val repository: ChatRepository) {
    suspend operator fun invoke(sessionId: String): TurnSnapshot? = repository.load(sessionId)?.let(TurnSnapshot::freeze)
}
