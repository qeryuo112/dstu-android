package com.deepstudent.features.notes.api

import com.deepstudent.core.common.id.ResourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NoteDocumentTest {
    @Test fun tagsHaveNoMutableAliases() {
        val tags = mutableListOf("first")
        val document = NoteDocument(
            "note_a", ResourceId.parse("res_a"), "title", "content", tags,
            NoteMetadata(false, NoteTimes("a", "b", null), null),
        )
        tags.add("second")
        assertEquals(listOf("first"), document.tags)
        assertThrows(UnsupportedOperationException::class.java) { (document.tags as MutableList).add("third") }
    }
}
