package com.deepstudent.platform.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The separate "close background" control. It asks the host to disable its own residency opt-in,
 * then withdraws every continuous purpose (terminal etc.). Real task duties such as an active
 * import are not cancelled; the service stops only when no duty remains.
 */
internal object CloseBackgroundAction {
    const val ACTION = "com.deepstudent.platform.background.CLOSE_BACKGROUND"

    fun intent(context: Context): Intent = Intent(context, CloseBackgroundReceiver::class.java).setAction(ACTION)
}

class CloseBackgroundReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CloseBackgroundAction.ACTION) return
        val host = context.applicationContext as? RuntimeForegroundHost ?: return
        val pending = goAsync()
        host.foregroundScope.launch(Dispatchers.IO) {
            try {
                host.disableBackground()
            } catch (cancelled: CancellationException) {
                pending.finish()
                throw cancelled
            } catch (_: Exception) {
                ForegroundLog(host.foregroundLogger).event("background.close", state = "disable_failed")
            }
            host.foregroundDuties.retireAllContinuous()
            if (host.foregroundDuties.slots.value.isEmpty()) {
                runCatching { context.stopService(Intent(context, RuntimeForegroundService::class.java)) }
            }
            pending.finish()
        }
    }
}
