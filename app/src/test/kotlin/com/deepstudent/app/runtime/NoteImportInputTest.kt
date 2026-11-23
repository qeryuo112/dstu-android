package com.deepstudent.app.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteImportInputTest {
    @Test fun realImportRequiresHttpsAndRejectsEmbeddedCredentials() {
        assertTrue(validImportUrl("https://example.com/events", false))
        assertFalse(validImportUrl("https://user:secret@example.com/events", false))
        assertFalse(validImportUrl("https://example.com/events#fragment", false))
        assertFalse(validImportUrl("http://example.com/events", true))
        assertFalse(validImportUrl("https://example.com:65536/events", false))
        assertFalse(validImportUrl("file:///data/system", true))
    }

    @Test fun onlyTheIsolatedFixtureMayUseLoopbackHttp() {
        val local = "http://127.0.0.1:49000/events"
        assertTrue(validImportUrl(local, true))
        assertFalse(validImportUrl(local, false))
        assertFalse(validImportUrl("http://127.0.0.1.example.com/events", true))
    }
}
