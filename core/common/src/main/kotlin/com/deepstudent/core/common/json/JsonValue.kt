package com.deepstudent.core.common.json

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException
import java.util.Collections

sealed interface JsonValue {
    data object Null : JsonValue

    data class Bool(val value: Boolean) : JsonValue

    data class Text(val value: String) : JsonValue

    class Number private constructor(val literal: String) : JsonValue {
        init {
            if (!numberLiteral.matches(literal)) {
                throw IllegalArgumentException("invalid JSON number literal")
            }
        }

        override fun equals(other: Any?): Boolean =
            other is Number && other.literal == literal

        override fun hashCode(): Int = literal.hashCode()

        override fun toString(): String = literal

        fun asLongOrNull(): Long? = if (integerLiteral.matches(literal)) literal.toLongOrNull() else null

        fun isIntegerLiteral(): Boolean = integerLiteral.matches(literal)

        companion object {
            private val numberLiteral =
                Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
            private val integerLiteral = Regex("-?(?:0|[1-9][0-9]*)")

            fun integer(value: Long): Number = Number(value.toString())

            fun raw(literal: String): Number = Number(literal)
        }
    }

    class Array(values: List<JsonValue>) : JsonValue {
        val values: List<JsonValue> = Collections.unmodifiableList(ArrayList(values))

        override fun equals(other: Any?): Boolean =
            other is Array && other.values == values

        override fun hashCode(): Int = values.hashCode()

        override fun toString(): String = literal()
    }

    class Object private constructor(entries: Map<String, JsonValue>) : JsonValue {
        private val entries: Map<String, JsonValue> = Collections.unmodifiableMap(LinkedHashMap(entries))

        val size: Int get() = entries.size

        fun isEmpty(): Boolean = entries.isEmpty()

        fun keys(): List<String> = entries.keys.toList()

        operator fun get(key: String): JsonValue? = entries[key]

        fun containsKey(key: String): Boolean = entries.containsKey(key)

        fun asMap(): Map<String, JsonValue> = entries

        override fun equals(other: Any?): Boolean = other is Object && other.entries == entries

        override fun hashCode(): Int = entries.hashCode()

        override fun toString(): String = literal()

        class Builder {
            private val entries = LinkedHashMap<String, JsonValue>()

            fun put(key: String, value: JsonValue): Builder {
                entries[key] = value
                return this
            }

            fun put(key: String, value: String): Builder = put(key, Text(value))

            fun put(key: String, value: Long): Builder = put(key, Number.integer(value))

            fun put(key: String, value: Int): Builder = put(key, value.toLong())

            fun put(key: String, value: Boolean): Builder = put(key, Bool(value))

            fun putNull(key: String): Builder = put(key, Null)

            fun putIfNotNull(key: String, value: JsonValue?): Builder =
                if (value == null) this else put(key, value)

            fun putAll(values: Object): Builder {
                entries.putAll(values.asMap())
                return this
            }

            fun build(): Object = Object(entries)
        }

        companion object {
            val EMPTY: Object = Object(LinkedHashMap())

            fun of(vararg entries: Pair<String, JsonValue>): Object =
                Builder().apply { entries.forEach { put(it.first, it.second) } }.build()

            fun from(values: Map<String, JsonValue>): Object = Object(LinkedHashMap(values))
        }
    }

    fun canonical(): String = JsonWriter.write(this, sortedKeys = true)

    fun literal(): String = JsonWriter.write(this, sortedKeys = false)

    fun utf8Size(): Int = canonical().toByteArray(Charsets.UTF_8).size

    /**
     * Number of nested object/array levels. Scalars are depth 0. This matches
     * the S0.24 SQLite json_tree depth where a root object is depth 1.
     */
    fun containerDepth(): Int = when (this) {
        is Array -> 1 + (values.maxOfOrNull { it.containerDepth() } ?: 0)
        is Object -> 1 + (asMap().values.maxOfOrNull { it.containerDepth() } ?: 0)
        else -> 0
    }

    companion object {
        fun obj(vararg entries: Pair<String, JsonValue>): Object = Object.of(*entries)

        fun array(values: List<JsonValue>): Array = Array(values)

        fun text(value: String): Text = Text(value)

        fun integer(value: Long): Number = Number.integer(value)

        fun bool(value: Boolean): Bool = Bool(value)

        fun parse(text: String): JsonValue = JsonParser.parse(text)

        fun parseObject(text: String, label: String = "JSON"): Object = JsonParser.parseObject(text, label)
    }
}

fun jsonError(code: String, message: String): Nothing =
    throw DstuException(DstuError.validation(code, message))
