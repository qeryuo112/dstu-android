package com.deepstudent.features.notes.backend

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.notes.backend.domain.NoteAssetReference
import com.deepstudent.features.notes.backend.domain.NoteAssetReferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteAssetReferencesTest {
    @Test fun frozenDesktopAssetManifestFixture() {
        val content = "![x](notes_assets/_global/n/a%20b.png) <img src=\"notes_assets/_global/n/a b.png\"> " +
            "[pdf](pdfref://file_123?page=4) [web](https://example.test/x)"
        val refs = NoteAssetReferences.extract(content, null)
        assertTrue(NoteAssetReference("notes_asset", "notes_assets/_global/n/a b.png") in refs)
        assertTrue(NoteAssetReference("external_resource", "pdfref://file_123?page=4") in refs)
        assertTrue(NoteAssetReference("remote_url", "https://example.test/x") in refs)
    }

    @Test fun percentDecodingIsStrictAndPlusIsLiteral() {
        val content = "notes_assets/a/%E4%B8%AD+ok.png notes_assets/b/%E4%B8.png " +
            "notes_assets/c/a%20b%ZZ.png notes_assets/d/a+b.png"
        val refs = NoteAssetReferences.extract(content, null).map { it.value }
        assertEquals(
            listOf("notes_assets/a/中+ok.png", "notes_assets/b/%E4%B8.png", "notes_assets/c/a%20b%ZZ.png", "notes_assets/d/a+b.png"),
            refs,
        )
    }

    @Test fun propertyStringsAndDuplicateReferencesFollowDesktop() {
        val props = JsonValue.obj("p" to JsonValue.text("notes_assets/a/p.png"), "nested" to
            JsonValue.obj("p" to JsonValue.text("notes_assets/not-scanned.png")))
        val refs = NoteAssetReferences.extract("notes_assets/a/p.png notes_assets\\a\\p.png", props)
        assertEquals(listOf(NoteAssetReference("notes_asset", "notes_assets/a/p.png")), refs)
    }

    @Test fun whitespaceMatchesEcmascriptInsteadOfPlatformRegexModes() {
        val whitespace = listOf('\u0009', '\u000A', '\u000B', '\u000C', '\u000D', ' ', '\u00A0',
            '\u1680', '\u2000', '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF')
        for (separator in whitespace) {
            val content = "notes_assets/a.png${separator}https://example.test/x${separator}suffix"
            val refs = NoteAssetReferences.extract(content, null)
            assertEquals(listOf(
                NoteAssetReference("notes_asset", "notes_assets/a.png"),
                NoteAssetReference("remote_url", "https://example.test/x"),
            ), refs)
        }
        assertEquals(
            listOf(NoteAssetReference("notes_asset", "notes_assets/a\u0085b.png")),
            NoteAssetReferences.extract("notes_assets/a\u0085b.png", null),
        )
    }
}
