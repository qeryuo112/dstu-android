package com.deepstudent.platform.background

import android.content.Context

/** Reads whether this app's background notifications (and the background channel) are blocked. */
object BackgroundNotifications {
    fun blocked(context: Context): Boolean = !TaskNotificationFactory(context.applicationContext).available()
}
