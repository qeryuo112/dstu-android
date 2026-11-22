package com.deepstudent.features.notes.backend.application

import com.deepstudent.features.notes.api.NoteApi
import com.deepstudent.features.notes.backend.domain.Note

class NoteUseCase(private val api: NoteApi) {
    fun create(): Note = Note(api.id().value)
}
