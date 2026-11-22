package android.graphics

object Color {
    const val BLACK = -16_777_216
    const val WHITE = -1

    @JvmStatic
    fun parseColor(value: String): Int {
        val hex = value.removePrefix("#")
        return if (hex.length == 6) {
            (0xff shl 24) or hex.toInt(16)
        } else {
            hex.toLong(16).toInt()
        }
    }

    @JvmStatic
    fun rgb(red: Int, green: Int, blue: Int): Int =
        (0xff shl 24) or (red shl 16) or (green shl 8) or blue
}
