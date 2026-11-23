package com.deepstudent.app.scheduling

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.work.Configuration
import com.deepstudent.app.MainActivity
import com.deepstudent.app.graph.AndroidGraphLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun mainWorkManagerConfiguration(isMain: Boolean): Configuration {
    check(isMain) { "WorkManager belongs to the main process" }
    return Configuration.Builder().setMinimumLoggingLevel(Log.WARN).build()
}

internal fun CoroutineScope.refreshVisibleSchedules(schedules: () -> MainSchedules) = launch {
    try {
        schedules().replanAll("visible_open")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        AndroidGraphLogger.info("background.schedule.replan", mapOf("state" to "failed"))
    }
}

internal fun Application.schedulePanelIntent(): Intent = Intent(this, MainActivity::class.java)
    .setAction(MainActivity.OPEN_SCHEDULES)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
