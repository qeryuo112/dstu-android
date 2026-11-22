package com.deepstudent.features.notes.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deepstudent.features.notes.backend.domain.NoteAssetReference
import com.deepstudent.features.notes.backend.domain.NoteAssetReferences
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteAssetReferencesAndroidTest {
    @Test fun androidRegexAndPercentDecodeMatchDesktop() {
        val content = "notes_assets/a/%E4%B8%AD+ok.png\u00a0https://example.test/a\uFEFFsuffix"
        assertEquals(
            listOf(
                NoteAssetReference("notes_asset", "notes_assets/a/中+ok.png"),
                NoteAssetReference("remote_url", "https://example.test/a"),
            ),
            NoteAssetReferences.extract(content, null),
        )
    }
}
