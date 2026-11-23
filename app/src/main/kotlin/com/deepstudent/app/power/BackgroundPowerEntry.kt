package com.deepstudent.app.power

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.deepstudent.app.terminal.TerminalResidency
import com.deepstudent.platform.background.BackgroundNotifications
import com.deepstudent.platform.background.BatteryOptimization
import com.deepstudent.platform.background.BatterySettingsTarget
import kotlinx.coroutines.launch

@Composable
@Suppress("ktlint:standard:function-naming")
internal fun BackgroundPowerEntry(residency: TerminalResidency) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val battery = remember(context) { BatteryOptimization(context) }
    val enabled by residency.enabled.collectAsState()
    val duties by residency.duties.collectAsState()
    var status by remember(battery) { mutableStateOf(battery.read()) }
    var blocked by remember(context) { mutableStateOf(BackgroundNotifications.blocked(context)) }
    val refresh: () -> Unit = {
        status = battery.read()
        blocked = BackgroundNotifications.blocked(context)
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh()
        scope.launch { residency.refresh() }
    }
    BackgroundPowerPanel(
        BackgroundPowerUi(
            enabled,
            duties.size,
            blocked,
            BatteryOptimizationUi(status.ignoringOptimizations, status.vendor, status.settingsTarget != BatterySettingsTarget.NONE),
            onKeepAliveChange = { desired ->
                scope.launch {
                    residency.setEnabled(desired)
                    if (desired && blocked && android.os.Build.VERSION.SDK_INT >= 33 &&
                        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        request.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            },
            onChangeBatterySettings = { battery.openSettings() },
            onRefresh = refresh,
        ),
    )
}
