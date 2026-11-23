package com.deepstudent.platform.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryVendorTest {
    @Test
    fun knownManufacturersMapToTheirOwnBucket() {
        assertEquals(BatteryVendor.VIVO, BatteryVendor.from("vivo", "vivo"))
        assertEquals(BatteryVendor.IQOO, BatteryVendor.from("vivo", "iQOO"))
        assertEquals(BatteryVendor.OPPO, BatteryVendor.from("OPPO", "OPPO"))
        assertEquals(BatteryVendor.ONEPLUS, BatteryVendor.from("OnePlus", "OnePlus"))
        assertEquals(BatteryVendor.REALME, BatteryVendor.from("realme", "realme"))
        assertEquals(BatteryVendor.XIAOMI, BatteryVendor.from("Xiaomi", "Xiaomi"))
        assertEquals(BatteryVendor.XIAOMI, BatteryVendor.from("Xiaomi", "POCO"))
        assertEquals(BatteryVendor.REDMI, BatteryVendor.from("Xiaomi", "Redmi"))
        assertEquals(BatteryVendor.HUAWEI, BatteryVendor.from("HUAWEI", "HUAWEI"))
        assertEquals(BatteryVendor.HONOR, BatteryVendor.from("HONOR", "HONOR"))
        assertEquals(BatteryVendor.SAMSUNG, BatteryVendor.from("samsung", "samsung"))
        assertEquals(BatteryVendor.MEIZU, BatteryVendor.from("Meizu", "Meizu"))
    }

    @Test
    fun iqooWinsOverItsVivoManufacturerBecauseTheBrandIsMoreSpecific() {
        assertEquals(BatteryVendor.IQOO, BatteryVendor.from("vivo", "iQOO"))
    }

    @Test
    fun unknownOrMissingBuildFieldsStayOther() {
        assertEquals(BatteryVendor.OTHER, BatteryVendor.from("Google", "google"))
        assertEquals(BatteryVendor.OTHER, BatteryVendor.from(null, null))
        assertEquals(BatteryVendor.OTHER, BatteryVendor.from("", ""))
    }

    @Test
    fun anUnknownVendorHasNoExtraCopy() {
        assertNull(BatteryVendor.OTHER.hintRes)
    }
}
