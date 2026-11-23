package com.deepstudent.platform.background

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.util.Locale

/**
 * Stable manufacturer bucket. The copy that explains where the vendor hides its setting lives with
 * the string resources, not in code.
 */
enum class BatteryVendor(val hintRes: Int?, private vararg val aliases: String) {
    IQOO(R.string.battery_vendor_iqoo, "iqoo"),
    VIVO(R.string.battery_vendor_vivo, "vivo"),
    ONEPLUS(R.string.battery_vendor_oneplus, "oneplus"),
    REALME(R.string.battery_vendor_realme, "realme"),
    OPPO(R.string.battery_vendor_oppo, "oppo"),
    REDMI(R.string.battery_vendor_redmi, "redmi"),
    XIAOMI(R.string.battery_vendor_xiaomi, "xiaomi", "poco"),
    HONOR(R.string.battery_vendor_honor, "honor"),
    HUAWEI(R.string.battery_vendor_huawei, "huawei"),
    SAMSUNG(R.string.battery_vendor_samsung, "samsung"),
    MEIZU(R.string.battery_vendor_meizu, "meizu"),
    OTHER(null),
    ;

    companion object {
        fun from(manufacturer: String?, brand: String?): BatteryVendor {
            val names = listOfNotNull(manufacturer, brand).joinToString(" ").lowercase(Locale.ROOT)
            return entries.firstOrNull { vendor -> vendor.aliases.any { it in names } } ?: OTHER
        }
    }
}

/** Which system page the user should be sent to. */
enum class BatterySettingsTarget {
    /** Standard battery optimization list (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`). */
    IGNORE_LIST,

    /** App details page; the fallback when the list page cannot be opened. */
    APP_DETAILS,

    /** No settings page could be opened. */
    NONE,
}

data class BatteryOptimizationState(
    val ignoringOptimizations: Boolean,
    val vendor: BatteryVendor,
    val settingsTarget: BatterySettingsTarget,
)

/**
 * Reads the real battery-optimization state and sends the user to the system page.
 *
 * This never uses `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: that intent shows a direct
 * exemption dialog and is only usable with the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission,
 * which this app does not declare. Android does not grant that permission to ordinary apps, so the
 * honest path is the standard list page and then the app details page.
 */
class BatteryOptimization(
    context: Context,
    private val sdk: Int = Build.VERSION.SDK_INT,
    private val manufacturer: String? = Build.MANUFACTURER,
    private val brand: String? = Build.BRAND,
) {
    private val appContext = context.applicationContext

    fun read(): BatteryOptimizationState {
        val vendor = BatteryVendor.from(manufacturer, brand)
        if (sdk < Build.VERSION_CODES.M) {
            // Battery optimization does not exist before Android 6; nothing restricts the app.
            return BatteryOptimizationState(
                ignoringOptimizations = true,
                vendor = vendor,
                settingsTarget = BatterySettingsTarget.NONE,
            )
        }
        val power = appContext.getSystemService(PowerManager::class.java)
        val ignoring = runCatching { power?.isIgnoringBatteryOptimizations(appContext.packageName) == true }
            .getOrDefault(false)
        return BatteryOptimizationState(
            ignoringOptimizations = ignoring,
            vendor = vendor,
            settingsTarget = BatterySettingsTarget.IGNORE_LIST,
        )
    }

    /** Opens the standard page and reports which one actually opened. */
    fun openSettings(): BatterySettingsTarget {
        if (sdk < Build.VERSION_CODES.M) return BatterySettingsTarget.NONE
        if (launch(ignoreListIntent())) {
            return BatterySettingsTarget.IGNORE_LIST
        }
        if (launch(appDetailsIntent())) return BatterySettingsTarget.APP_DETAILS
        return BatterySettingsTarget.NONE
    }

    /** Standard battery optimization list; the system settings app owns it. */
    internal fun ignoreListIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** App details page for this package, used when the list page is unavailable. */
    internal fun appDetailsIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", appContext.packageName, null),
    )

    private fun launch(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            appContext.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
