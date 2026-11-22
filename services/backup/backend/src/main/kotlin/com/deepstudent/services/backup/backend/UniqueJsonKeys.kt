package com.deepstudent.services.backup.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/** Reject ambiguous duplicate JSON object keys, including differently escaped spellings. */
internal class UniqueJsonKeys(private val raw: String, private val checkCancelled: () -> Unit) {
    private var index = 0
    fun check() { value(0); whitespace(); require(index == raw.length) }
    private fun whitespace() { while (index < raw.length && raw[index] in " \t\r\n") index++ }
    private fun take(char: Char) { whitespace(); require(index < raw.length && raw[index++] == char) }
    private fun value(depth: Int) {
        if (index and 0x3fff == 0) checkCancelled()
        require(depth <= 64)
        whitespace(); require(index < raw.length)
        when (raw[index]) {
            '{' -> obj(depth + 1)
            '[' -> array(depth + 1)
            '"' -> string()
            else -> {
                val start = index
                while (index < raw.length && raw[index] !in ",]} \t\r\n") index++
                require(index > start)
            }
        }
    }
    private fun obj(depth: Int) {
        take('{'); whitespace()
        if (index < raw.length && raw[index] == '}') { index++; return }
        val keys = HashSet<String>()
        while (true) {
            whitespace(); require(index < raw.length && raw[index] == '"')
            require(keys.add(string())) { "duplicate JSON object key" }
            take(':'); value(depth); whitespace()
            require(index < raw.length)
            if (raw[index] == '}') { index++; return }
            take(',')
        }
    }
    private fun array(depth: Int) {
        take('['); whitespace()
        if (index < raw.length && raw[index] == ']') { index++; return }
        while (true) {
            value(depth); whitespace(); require(index < raw.length)
            if (raw[index] == ']') { index++; return }
            take(',')
        }
    }
    private fun string(): String {
        val start = index
        require(raw[index++] == '"')
        var escaped = false
        while (index < raw.length) {
            if (index and 0x3fff == 0) checkCancelled()
            val ch = raw[index++]
            if (!escaped && ch == '"') return Json.parseToJsonElement(raw.substring(start, index)).jsonPrimitive.content
            if (!escaped && ch == '\\') escaped = true else escaped = false
        }
        error("unterminated JSON string")
    }
}
