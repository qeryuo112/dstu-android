package com.deepstudent.services.resources.data.assets

import com.deepstudent.services.resources.api.assets.AssetScanException
import com.deepstudent.services.resources.api.assets.AssetScanFailure
import java.text.Normalizer
import java.util.Locale

/** Only names whose frozen desktop sanitizer is exactly the identity are accepted. */
internal fun portableSegment(value: String): String {
    validateStructure(value)
    if (value.last() == '.' || value.last() == ' ' || value.any { invalidPortableChar(it) }) {
        throw AssetScanException(AssetScanFailure.UNSUPPORTED_NAME)
    }
    val stem = value.substringBefore('.').trimEnd(' ').uppercase(Locale.ROOT)
    if (stem in RESERVED) throw AssetScanException(AssetScanFailure.UNSUPPORTED_NAME)
    return value
}

private fun validateStructure(value: String) {
    if (value.isEmpty() || value == "." || value == ".." || value.length > 255 ||
        value.toByteArray(Charsets.UTF_8).size > 255 || !Normalizer.isNormalized(value, Normalizer.Form.NFC)) {
        throw AssetScanException(AssetScanFailure.UNSUPPORTED_NAME)
    }
}

internal fun validatePortableKeyLength(key: String) {
    if (key.length > 240 || key.toByteArray(Charsets.UTF_8).size > 240) throw AssetScanException(AssetScanFailure.LIMIT)
}

private fun invalidPortableChar(char: Char): Boolean = char in "\\/:*?\"<>|‛＂＊：＜＞？＼｜／␠␡．\uFFFD" ||
    char.code in 0x2400..0x241f || Character.getType(char) == Character.CONTROL.toInt() || Character.isSurrogate(char)

private val RESERVED = setOf("CON", "PRN", "AUX", "NUL") +
    (0..9).flatMap { listOf("COM$it", "LPT$it") }
