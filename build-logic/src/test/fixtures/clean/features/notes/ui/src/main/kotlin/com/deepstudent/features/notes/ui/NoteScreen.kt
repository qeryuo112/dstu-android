package com.deepstudent.features.notes.ui

import com.deepstudent.features.notes.api.NoteApi

class NoteScreen(private val api: NoteApi) {
    fun title(): String = api.id().value
}
