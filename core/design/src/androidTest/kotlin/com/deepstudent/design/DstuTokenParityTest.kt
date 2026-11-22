package com.deepstudent.design

import androidx.compose.ui.graphics.toArgb
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DstuTokenParityTest {
    private val baseline: JSONObject by lazy {
        val context = InstrumentationRegistry.getInstrumentation().context
        JSONObject(context.assets.open("s1-design-baseline.json").bufferedReader().use { it.readText() })
    }

    @Test
    fun everyResolvedPaletteColorMatchesFrozenBrowserSnapshot() {
        for (mode in DstuMode.entries) for (palette in DstuPalette.entries) {
            val id = "${mode.id}/${palette.id}"
            val expected = colors(id, mode)
            val actual = DstuTokens.resolve(mode, palette).colors.values
            assertEquals("$id color names", expected.keys, actual.keys)
            assertEquals("$id color count", 329, actual.size)
            for ((name, argb) in expected) assertEquals("$id $name", argb, actual.getValue(name).toArgb())
        }
    }

    private fun colors(id: String, mode: DstuMode): Map<String, Int> {
        val base = baseline.getJSONObject("scenarios").getJSONObject("${mode.id}/default").getJSONObject("tokens")
        val runtime = baseline.getJSONObject("palettesRuntime").getJSONObject(id)
        val tokens = LinkedHashMap<String, JSONObject>()
        merge(tokens, base)
        merge(tokens, runtime.getJSONObject("differing"))
        merge(tokens, runtime.getJSONObject("accent"))
        return tokens.mapNotNull { (name, token) ->
            val kotlin = token.getJSONObject("kotlin")
            if (kotlin.getString("type") != "Color") null
            else name to kotlin.getString("value").removePrefix("0x").toLong(16).toInt()
        }.toMap()
    }

    private fun merge(target: MutableMap<String, JSONObject>, source: JSONObject) {
        source.keys().forEach { target[it] = source.getJSONObject(it) }
    }

    @Test
    fun resolvedTypeAndSizeMetricsMatchFrozenSnapshot() {
        for (mode in DstuMode.entries) {
            val expected = baseline.getJSONObject("scenarios").getJSONObject("${mode.id}/default").getJSONObject("tokens")
            val actual = DstuTokens.resolve(mode)
            var matched = 0
            for (name in expected.keys()) {
                val token = expected.getJSONObject(name)
                if (token.getString("res") != "resolved") continue
                val kotlin = token.getJSONObject("kotlin")
                val type = kotlin.getString("type")
                val field = when (type) { "Dp" -> "dp"; "TextUnit" -> "sp"; else -> "value" }
                val value = kotlin.opt(field)
                if (value !is Number) continue
                val found: Number = when (type) {
                    "Dp" -> actual.sizes.all[name]
                    "TextUnit" -> actual.type.sizes[name]
                    "Float" -> actual.type.numbers[name]
                    "Int" -> actual.type.weights[name]
                    else -> null
                } ?: continue
                if (type == "Int") assertEquals("${mode.id} $name", value.toLong(), found.toLong())
                else assertEquals("${mode.id} $name", value.toDouble(), found.toDouble(), 0.00001)
                matched++
            }
            assertEquals("${mode.id} metric coverage", 195, matched)
        }
    }

    @Test
    fun paletteChangesAccentsWithoutReplacingNeutralColors() {
        val defaults = DstuTokens.resolve(DstuMode.Light).colors
        val purple = DstuTokens.resolve(DstuMode.Light, DstuPalette.Purple).colors
        assertEquals(defaults.background, purple.background)
        assertEquals(defaults.foreground, purple.foreground)
        assertNotEquals(defaults.primary, purple.primary)
    }
}
