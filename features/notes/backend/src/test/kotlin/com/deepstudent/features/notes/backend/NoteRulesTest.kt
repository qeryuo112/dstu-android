package com.deepstudent.features.notes.backend

import com.deepstudent.core.common.error.DstuException
import com.deepstudent.features.notes.backend.domain.NoteRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NoteRulesTest {
    @Test fun contentHashIsSaltedByNoteIdentity() {
        assertEquals(
            "6762f9adb2f50f25927af0e1a49a55f387ce7e3de7eb63b2cc9f79ed7e0ac566",
            NoteRules.saltedContentHash("note_test", "hello"),
        )
        assertNotEquals(NoteRules.saltedContentHash("note_a", "hello"), NoteRules.saltedContentHash("note_b", "hello"))
    }

    @Test fun titleAndTagsFollowDesktopBoundsAndControls() {
        NoteRules.validateTitle("  标题 🧪  ")
        NoteRules.validateTitle("🧪".repeat(500))
        NoteRules.validateTags(listOf("标签", "🧪".repeat(100)))
        assertThrows(DstuException::class.java) { NoteRules.validateTitle("\u3000") }
        assertThrows(DstuException::class.java) { NoteRules.validateTitle("\ninvalid") }
        assertThrows(DstuException::class.java) { NoteRules.validateTitle("🧪".repeat(501)) }
        assertThrows(DstuException::class.java) { NoteRules.validateTags(List(101) { "x" }) }
        assertThrows(DstuException::class.java) { NoteRules.validateTags(listOf("x\n")) }
    }
}
