package com.deepstudent.features.editor.api

/** Half-open offsets in UTF-16 code units, matching Kotlin String and desktop JavaScript. */
data class SourceRange(val start: Int, val endExclusive: Int) {
    init {
        require(start >= 0 && endExclusive >= start) { "Invalid source range" }
    }

    val length: Int get() = endExclusive - start

    fun textOf(source: String): String {
        require(endExclusive <= source.length) { "Source range exceeds source" }
        require(isTextBoundary(source, start) && isTextBoundary(source, endExclusive)) {
            "Source range splits a surrogate pair or CRLF"
        }
        return source.substring(start, endExclusive)
    }
}

/** A boundary may not split a Unicode scalar value or a CRLF line ending. */
fun isTextBoundary(source: String, offset: Int): Boolean {
    if (offset !in 0..source.length) return false
    if (offset == 0 || offset == source.length) return true
    val before = source[offset - 1]
    val after = source[offset]
    return !(before.isHighSurrogate() && after.isLowSurrogate()) && !(before == '\r' && after == '\n')
}
