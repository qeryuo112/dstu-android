package com.deepstudent.platform.background

/** Sticky restarts exist only for user opt-in plus a continuous duty with a real recovery protocol. */
internal object ForegroundSticky {
    fun shouldStick(host: RuntimeForegroundHost, continuous: List<ContinuousDuty>): Boolean =
        host.isBackgroundStickyOptedIn() && continuous.any(host::isColdRecoverableContinuousDuty)
}
