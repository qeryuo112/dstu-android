package com.deepstudent.core.common.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonValueTest {
    @Test
    fun canonicalJsonSortsKeysAndPreservesIntegerAndRealTokens() {
        val value = JsonValue.parse(
            """{"z":9007199254740993,"a":-9223372036854775808,"n":null,"s":"中\n\u0001","b":1.0,"m":9223372036854775807}""",
        )
        assertEquals(
            """{"a":-9223372036854775808,"b":1.0,"m":9223372036854775807,"n":null,"s":"中\n\u0001","z":9007199254740993}""",
            value.canonical(),
        )
        assertEquals("1.0", (value as JsonValue.Object)["b"].toString())
    }

    @Test
    fun literalPreservesInsertionOrderAndEscapesJavascriptStyle() {
        val value = JsonValue.obj(
            "z" to JsonValue.text("a\"b\\c\n\u0001中"),
            "a" to JsonValue.Text("\uD800"),
        )
        assertEquals("""{"z":"a\"b\\c\n\u0001中","a":"\ud800"}""", value.literal())
    }

    @Test
    fun containerDepthMatchesSqliteJsonTreeShape() {
        val sixteen = JsonValue.parse("[[[[[[[[[[[[[[[[]]]]]]]]]]]]]]]]")
        val seventeen = JsonValue.parse("[[[[[[[[[[[[[[[[[]]]]]]]]]]]]]]]]]")
        assertEquals(16, sixteen.containerDepth())
        assertEquals(17, seventeen.containerDepth())
        assertEquals(1, JsonValue.Object.EMPTY.containerDepth())
        assertEquals(1, JsonValue.obj("n" to JsonValue.integer(1)).containerDepth())
    }

    @Test
    fun fieldsDistinguishNullFromAbsent() {
        val value = JsonValue.obj("present" to JsonValue.Null)
        assertTrue(value.containsKey("present"))
        assertFalse(value.containsKey("absent"))
        assertNull(JsonFields.optionalString(value, "present"))
        assertEquals(JsonValue.Null, value["present"])
    }

    @Test
    fun utf8SizeUsesEncodedBytes() {
        assertEquals(5, JsonValue.text("中").utf8Size())
        assertEquals(3, JsonValue.text("a").utf8Size())
    }

    @Test
    fun objectBuilderAndSourceMapCannotMutateBuiltValue() {
        val source = mutableMapOf("a" to JsonValue.integer(1))
        val objectValue = JsonValue.Object.from(source)
        source["b"] = JsonValue.integer(2)
        assertEquals(listOf("a"), objectValue.keys())

        val builder = JsonValue.Object.Builder().put("a", JsonValue.integer(1))
        val built = builder.build()
        builder.put("b", JsonValue.integer(2))
        assertEquals(listOf("a"), built.keys())

        @Suppress("UNCHECKED_CAST")
        val cast = objectValue.asMap() as MutableMap<String, JsonValue>
        assertThrows(UnsupportedOperationException::class.java) {
            cast.put("b", JsonValue.integer(2))
        }
    }

    @Test
    fun arrayCopiesMutableInputAndExposesUnmodifiableList() {
        val source = mutableListOf<JsonValue>(JsonValue.integer(1))
        val arrayValue = JsonValue.Array(source)
        source += JsonValue.integer(2)
        assertEquals(1, arrayValue.values.size)

        @Suppress("UNCHECKED_CAST")
        val cast = arrayValue.values as MutableList<JsonValue>
        assertThrows(UnsupportedOperationException::class.java) {
            cast.add(JsonValue.integer(2))
        }
    }

    @Test
    fun invalidJsonMessageNeverEchoesPayload() {
        val failure = assertThrows(com.deepstudent.core.common.error.DstuException::class.java) {
            JsonValue.parse("""{"secret":"TOP-SECRET-CONTENT","broken":}""")
        }
        assertEquals("invalid JSON", failure.error.message)
        assertFalse(failure.error.message.contains("TOP-SECRET-CONTENT"))
    }
}
