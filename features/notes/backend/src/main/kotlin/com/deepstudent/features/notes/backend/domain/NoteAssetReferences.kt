package com.deepstudent.features.notes.backend.domain

import com.deepstudent.core.common.json.JsonValue
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class NoteAssetReference(val kind: String, val value: String)

/** Desktop e6b2657, host/plugins/notes/repository/revisions.ts:21-84. */
object NoteAssetReferences {
    private const val JS_WHITESPACE =
        "\\u0009-\\u000D\\u0020\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    private val owned = Regex("notes_assets[/\\\\][^$JS_WHITESPACE<>\"'()\\[\\]{},?#]+")
    private val quoted = Regex("[<\"']([^<>\"']*notes_assets[/\\\\][^<>\"']+)[>\"']")
    private val external = Regex("(?:https?://|pdfref://|file://)[^$JS_WHITESPACE<>\"'()\\[\\]{}]+")

    fun extract(content: String, props: JsonValue?): List<NoteAssetReference> {
        val references = mutableSetOf<NoteAssetReference>()
        collect(content, references)
        (props as? JsonValue.Object)?.asMap()?.values?.filterIsInstance<JsonValue.Text>()?.forEach {
            collect(it.value, references)
        }
        return references.sortedWith(compareBy(NoteAssetReference::kind, NoteAssetReference::value))
    }

    fun json(content: String, props: JsonValue?): String = JsonValue.array(
        extract(content, props).map { ref ->
            JsonValue.obj("kind" to JsonValue.text(ref.kind), "value" to JsonValue.text(ref.value))
        },
    ).literal()

    private fun collect(content: String, references: MutableSet<NoteAssetReference>) {
        owned.findAll(content).forEach { addOwned(references, it.value) }
        quoted.findAll(content).forEach {
            val raw = it.groupValues[1]
            addOwned(references, raw.substring(raw.indexOf("notes_assets")))
        }
        external.findAll(content).filterNot { "notes_assets" in it.value }.forEach {
            val kind = if (it.value.startsWith("http")) "remote_url" else "external_resource"
            references.add(NoteAssetReference(kind, it.value))
        }
    }

    private fun addOwned(references: MutableSet<NoteAssetReference>, raw: String) {
        val decoded = try { decodeUriComponent(raw) } catch (_: IllegalArgumentException) { raw }
        references.add(NoteAssetReference("notes_asset", decoded.replace('\\', '/')))
    }
}

/** Mirrors decodeURIComponent: '+' is literal, invalid UTF-8 preserves the complete original value. */
private fun decodeUriComponent(value: String): String {
    val output = StringBuilder()
    var index = 0
    while (index < value.length) {
        if (value[index] != '%') {
            output.append(value[index++])
            continue
        }
        val group = readPercentGroup(value, index)
        output.append(decodeUtf8(group.bytes))
        index = group.end
    }
    return output.toString()
}

private data class PercentGroup(val bytes: ByteArray, val end: Int)

private fun readPercentGroup(value: String, start: Int): PercentGroup {
    val bytes = ByteArrayOutputStream()
    var index = start
    while (index < value.length && value[index] == '%') {
        if (index + 2 >= value.length) throw IllegalArgumentException("invalid percent encoding")
        val high = value[index + 1].digitToIntOrNull(16) ?: throw IllegalArgumentException("invalid percent encoding")
        val low = value[index + 2].digitToIntOrNull(16) ?: throw IllegalArgumentException("invalid percent encoding")
        bytes.write(high * 16 + low)
        index += 3
    }
    return PercentGroup(bytes.toByteArray(), index)
}

private fun decodeUtf8(bytes: ByteArray): String = try {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    decoder.decode(ByteBuffer.wrap(bytes)).toString()
} catch (failure: java.nio.charset.CharacterCodingException) {
    throw IllegalArgumentException("invalid UTF-8")
}
