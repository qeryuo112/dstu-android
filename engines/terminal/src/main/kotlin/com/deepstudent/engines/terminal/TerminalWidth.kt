package com.deepstudent.engines.terminal

import com.ibm.icu.lang.UCharacter
import com.ibm.icu.lang.UProperty

internal object TerminalWidth {
    fun isWide(codePoint: Int): Boolean {
        val width = UCharacter.getIntPropertyValue(codePoint, UProperty.EAST_ASIAN_WIDTH)
        return width == UCharacter.EastAsianWidth.WIDE ||
            width == UCharacter.EastAsianWidth.FULLWIDTH ||
            (codePoint > 0xffff && UCharacter.hasBinaryProperty(codePoint, UProperty.EMOJI)) ||
            UCharacter.hasBinaryProperty(codePoint, UProperty.EMOJI_PRESENTATION)
    }
}
