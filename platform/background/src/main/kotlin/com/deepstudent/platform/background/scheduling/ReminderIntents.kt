package com.deepstudent.platform.background.scheduling

import android.content.Context
import android.content.Intent
import android.net.Uri

internal object ReminderActions {
    const val DUE = "com.deepstudent.platform.background.action.REMINDER_DUE"
}

/** Explicit immutable intents; identity is the ReminderIdentity encoding of schedule+occurrence. */
internal object ReminderIntents {
    fun due(context: Context, fire: ReminderFire): Intent = Intent(context, ReminderAlarmReceiver::class.java)
        .setAction(ReminderActions.DUE)
        .setData(Uri.parse(ReminderIdentity.encode(fire)))

    fun parse(intent: Intent, expectedAction: String): ReminderFire? {
        if (intent.action != expectedAction) return null
        return ReminderIdentity.decode(intent.dataString)
    }
}
