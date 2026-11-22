package com.deepstudent.core.common.number

import com.deepstudent.core.common.error.DstuException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Int64BoundsTest {
    @Test
    fun acceptsInt64Endpoints() {
        assertEquals(Long.MIN_VALUE, Int64Bounds.parseInt64("-9223372036854775808"))
        assertEquals(Long.MAX_VALUE, Int64Bounds.parseInt64("9223372036854775807"))
    }

    @Test
    fun rejectsValuesOutsideInt64() {
        assertThrows(DstuException::class.java) {
            Int64Bounds.parseInt64("9223372036854775808")
        }
        assertThrows(DstuException::class.java) {
            Int64Bounds.parseInt64("-9223372036854775809")
        }
        assertThrows(DstuException::class.java) { Int64Bounds.parseInt64("1.0") }
    }

    @Test
    fun jsonSafeDomainUsesDesktopNumberMaxSafeInteger() {
        assertTrue(Int64Bounds.isJsonSafe(Int64Bounds.JSON_SAFE_MAX))
        assertTrue(Int64Bounds.isJsonSafe(Int64Bounds.JSON_SAFE_MIN))
        assertFalse(Int64Bounds.isJsonSafe(Int64Bounds.JSON_SAFE_MAX + 1))
        assertFalse(Int64Bounds.isJsonSafe(Int64Bounds.JSON_SAFE_MIN - 1))
        assertEquals(9_007_199_254_740_991L, Int64Bounds.JSON_SAFE_MAX)
    }
}
