package com.deepstudent.features.notes.data

import com.deepstudent.core.common.id.ResourceId
import com.deepstudent.features.notes.api.NoteDocument
import com.deepstudent.features.notes.api.NoteDraftSnapshot
import com.deepstudent.features.notes.api.NoteMetadata
import com.deepstudent.features.notes.api.NoteTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NoteDraftCodecTest {
    @Test fun originalOccRevisionAndUnicodeDraftSurviveRoundTrip() {
        val note = NoteDocument("note_1", ResourceId.parse("res_1"), "original", "source", listOf("tag"),
            NoteMetadata(false, NoteTimes("created", "revision-1", null), null))
        val restored = NoteDraftCodec.decode(NoteDraftCodec.encode(NoteDraftSnapshot(note, "标题", "未保存👩‍💻\r\n", listOf("标签"), 7)))
        assertEquals("revision-1", restored.original!!.updatedAt)
        assertEquals("未保存👩‍💻\r\n", restored.content)
        assertEquals(listOf("标签"), restored.tags)
        assertEquals(7L, restored.editorKey)
    }

    @Test fun newDraftDoesNotBecomeSavedAndUnknownVersionIsRejected() {
        val encoded = NoteDraftCodec.encode(NoteDraftSnapshot(null, "new", "body", emptyList(), 1))
        assertEquals(null, NoteDraftCodec.decode(encoded).original)
        assertThrows(IllegalArgumentException::class.java) { NoteDraftCodec.decode(encoded.replace("\"version\":1", "\"version\":2")) }
    }
}
