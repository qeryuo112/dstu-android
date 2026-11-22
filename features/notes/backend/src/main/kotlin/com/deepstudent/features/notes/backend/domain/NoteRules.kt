package com.deepstudent.features.notes.backend.domain

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.id.RustText

object NoteRules {
    fun validateTitle(title: String) {
        if (RustText.trim(title).isEmpty() || title.codePointCount(0, title.length) > 500) {
            invalid("INVALID_NOTE_TITLE")
        }
        if (title.any { it.code in 0..8 || it.code in 10..31 || it.code in 127..159 }) {
            invalid("INVALID_NOTE_TITLE")
        }
    }

    fun validateTags(tags: List<String>) {
        if (tags.size > 100) invalid("INVALID_NOTE_TAGS")
        for (tag in tags) {
            if (tag.codePointCount(0, tag.length) > 100 || tag.any { it.code in 0..31 || it.code in 127..159 }) {
                invalid("INVALID_NOTE_TAGS")
            }
        }
    }

    fun validateId(id: String) {
        if (!Regex("[A-Za-z0-9_-]{1,128}").matches(id)) invalid("INVALID_NOTE_ID")
    }

    fun saltedContentHash(noteId: String, content: String): String = Digests.sha256HexUtf8("$noteId:$content")

    private fun invalid(code: String): Nothing =
        throw DstuException(DstuError.validation(code, "invalid note input"))
}
