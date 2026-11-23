package com.deepstudent.app.power

import androidx.compose.runtime.Immutable
import com.deepstudent.platform.background.BatteryVendor

/** What the battery page shows about system battery optimization. */
@Immutable
data class BatteryOptimizationUi(
    /** `PowerManager.isIgnoringBatteryOptimizations(packageName)`. */
    val ignoringOptimizations: Boolean,
    val vendor: BatteryVendor,
    /** False when neither the standard battery page nor the app details page can be opened. */
    val settingsAvailable: Boolean,
)

/**
 * Everything the battery page renders, plus the callbacks the host wires to real behaviour.
 *
 * The panel never creates a duty, never writes business state and never touches DataStore: the host
 * reads [BackgroundPreferences.enabled], the live slots of `DutyRegistry`, notification blocking and
 * `BatteryOptimization.read()`, then supplies this model. Defaults keep previews and tests compiling.
 */
@Immutable
data class BackgroundPowerUi(
    val keepAliveEnabled: Boolean,
    /** Number of live registry slots: foreground duties plus continuous sessions. */
    val activeDuties: Int,
    val notificationsBlocked: Boolean,
    val battery: BatteryOptimizationUi,
    val onKeepAliveChange: (Boolean) -> Unit = {},
    val onChangeBatterySettings: () -> Unit = {},
    /** Called on ON_RESUME so the page re-reads preference, slots and battery state. */
    val onRefresh: () -> Unit = {},
) {
    init {
        require(activeDuties >= 0) { "active duty count cannot be negative" }
    }
}

/** Test tags the device tests rely on. */
object BackgroundPowerTags {
    const val PANEL = "background-power-panel"
    const val KEEP_ALIVE = "background-keep-alive"
    const val ACTIVE_DUTIES = "background-active-duties"
    const val BATTERY_STATUS = "background-battery-status"
}
