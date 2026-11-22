package com.deepstudent.features.notes.api

/** Local editor recovery, not portable note content or a replacement for OCC. */
data class NoteDraftSnapshot(
    val original: NoteDocument?,
    val title: String,
    val content: String,
    val tags: List<String>,
    val editorKey: Long,
)
