package com.deepstudent.platform.background

import android.content.pm.ServiceInfo
import android.os.Build

internal object ForegroundEligibility {
    /**
     * 0 means "no type flag": below API 29 and below API 34 for a purpose without a fitting type,
     * the service promotes with the two-argument startForeground. No purpose is ever faked as
     * dataSync just to obtain a type.
     */
    fun type(purpose: ForegroundPurpose, sdk: Int = Build.VERSION.SDK_INT): Int = when (purpose) {
        ForegroundPurpose.DATA_SYNC ->
            if (sdk >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0

        ForegroundPurpose.TERMINAL ->
            if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
    }

    fun types(slots: List<ForegroundSlot>, sdk: Int = Build.VERSION.SDK_INT): Int =
        slots.fold(0) { types, slot -> types or type(slot.purpose, sdk) }

    fun purposesForTimeoutType(fgsType: Int): Set<ForegroundPurpose> = when (fgsType) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC -> setOf(ForegroundPurpose.DATA_SYNC)
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE -> setOf(ForegroundPurpose.TERMINAL)
        else -> emptySet()
    }
}
