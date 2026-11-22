package com.deepstudent.platform.background

import android.content.pm.ServiceInfo
import android.os.Build

internal object ForegroundEligibility {
    fun type(purpose: ForegroundPurpose, sdk: Int): Int = when (purpose) {
        ForegroundPurpose.DATA_SYNC -> if (sdk >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
    }
}
