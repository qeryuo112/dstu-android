package com.deepstudent.app.power

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.deepstudent.app.R
import com.deepstudent.platform.background.BatteryVendor

/**
 * Battery page. It renders [model] only: no duty is created, no preference is written and no
 * business call is made here. The host implements the real toggle and settings callbacks.
 */
@Suppress("ktlint:standard:function-naming") // Compose entry points use PascalCase.
@Composable
fun BackgroundPowerPanel(model: BackgroundPowerUi, modifier: Modifier = Modifier) {
    RefreshOnResume(model.onRefresh)
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag(BackgroundPowerTags.PANEL),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.background_power_title), style = MaterialTheme.typography.titleMedium)
        KeepAliveRow(model)
        Text(
            stringResource(R.string.background_power_active_duties, model.activeDuties),
            modifier = Modifier.testTag(BackgroundPowerTags.ACTIVE_DUTIES),
        )
        Text(
            stringResource(
                if (model.notificationsBlocked) {
                    R.string.background_power_notifications_blocked
                } else {
                    R.string.background_power_notifications_visible
                },
            ),
        )
        BatterySection(model)
    }
}

@Suppress("ktlint:standard:function-naming") // Compose entry points use PascalCase.
@Composable
private fun KeepAliveRow(model: BackgroundPowerUi) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.background_power_keep_alive), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.background_power_keep_alive_detail), style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = model.keepAliveEnabled,
            onCheckedChange = model.onKeepAliveChange,
            modifier = Modifier.testTag(BackgroundPowerTags.KEEP_ALIVE),
        )
    }
}

@Suppress("ktlint:standard:function-naming") // Compose entry points use PascalCase.
@Composable
private fun BatterySection(model: BackgroundPowerUi) {
    val battery = model.battery
    Text(
        stringResource(
            if (battery.ignoringOptimizations) {
                R.string.background_power_battery_ignored
            } else {
                R.string.background_power_battery_optimized
            },
        ),
        modifier = Modifier.testTag(BackgroundPowerTags.BATTERY_STATUS),
    )
    if (battery.ignoringOptimizations) return
    if (battery.settingsAvailable) {
        Button(onClick = model.onChangeBatterySettings) {
            Text(stringResource(R.string.background_power_battery_open))
        }
    } else {
        Text(stringResource(R.string.background_power_battery_unavailable))
    }
    BatteryVendorHint(battery.vendor)
}

@Suppress("ktlint:standard:function-naming") // Compose entry points use PascalCase.
@Composable
private fun BatteryVendorHint(vendor: BatteryVendor) {
    val hint = vendor.hintRes ?: return
    Text(stringResource(hint), style = MaterialTheme.typography.bodySmall)
}

/** The battery state, notification state and active duties change outside the app. */
@Suppress("ktlint:standard:function-naming") // Compose entry points use PascalCase.
@Composable
private fun RefreshOnResume(onRefresh: () -> Unit) {
    val owner = LocalActivity.current as? LifecycleOwner
    val refresh = rememberUpdatedState(onRefresh)
    DisposableEffect(owner) {
        if (owner == null) return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh.value()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
