package com.deepstudent.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepstudent.design.generated.generatedBaseColors
import com.deepstudent.design.generated.generatedDp
import com.deepstudent.design.generated.generatedFloat
import com.deepstudent.design.generated.generatedInt
import com.deepstudent.design.generated.generatedOverrides
import com.deepstudent.design.generated.generatedTextUnit

enum class DstuMode(val id: String) { Light("light"), Dark("dark") }

enum class DstuPalette(val id: String) {
    Default("default"), Purple("purple"), Green("green"), Orange("orange"), Pink("pink"),
    BrightPink("bright-pink"), Teal("teal"), Muted("muted"), Paper("paper"), Custom("custom"),
}

@Immutable
class DstuColorScheme internal constructor(val values: Map<String, Color>) {
    operator fun get(token: String): Color = requireNotNull(values[token]) { "Unknown color token $token" }
    val primary: Color get() = this["--primary"]
    val onPrimary: Color get() = this["--primary-foreground"]
    val background: Color get() = this["--background"]
    val foreground: Color get() = this["--foreground"]
    val surface: Color get() = this["--surface-panel"]
    val border: Color get() = this["--border"]
    val mutedForeground: Color get() = this["--muted-foreground"]
}

@Immutable
class DstuSizes internal constructor(private val values: Map<String, Float>) {
    operator fun get(token: String): Dp = requireNotNull(values[token]) { "Unknown size token $token" }.dp
    val all: Map<String, Float> get() = values
    val minimumTouchTarget: Dp get() = 48.dp
}

@Immutable
class DstuTypeTokens internal constructor(
    val sizes: Map<String, Float>,
    val numbers: Map<String, Double>,
    val weights: Map<String, Int>,
) {
    fun size(token: String): TextUnit = requireNotNull(sizes[token]) { "Unknown text token $token" }.sp
    fun number(token: String): Float = requireNotNull(numbers[token]) { "Unknown number token $token" }.toFloat()
    fun weight(token: String): Int = requireNotNull(weights[token]) { "Unknown weight token $token" }
}

@Immutable
class DstuDesignTokens internal constructor(
    val colors: DstuColorScheme,
    val sizes: DstuSizes,
    val type: DstuTypeTokens,
)

object DstuTokens {
    private val scenarios by lazy {
        DstuMode.entries.associateWith { mode ->
            val base = generatedBaseColors(mode.id)
            DstuPalette.entries.associateWith { palette ->
                val colors = (base + generatedOverrides("${mode.id}/${palette.id}"))
                    .mapValues { Color(it.value) }
                DstuDesignTokens(
                    colors = DstuColorScheme(colors),
                    sizes = DstuSizes(generatedDp(mode.id)),
                    type = DstuTypeTokens(generatedTextUnit(mode.id), generatedFloat(mode.id), generatedInt(mode.id)),
                )
            }
        }
    }

    fun resolve(mode: DstuMode, palette: DstuPalette = DstuPalette.Default): DstuDesignTokens =
        scenarios.getValue(mode).getValue(palette)
}
