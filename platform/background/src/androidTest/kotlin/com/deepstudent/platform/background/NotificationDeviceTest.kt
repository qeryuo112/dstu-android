package com.deepstudent.platform.background

import android.app.PendingIntent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.services.runtime.api.RuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun stopActionsAreExplicitImmutableAndUniquePerTaskAndGeneration() {
        val first = duty("first", 1)
        val second = duty("second", 1)
        val next = duty("first", 2)
        val firstIntent = StopTaskAction.intent(context, first)
        assertEquals(StopTaskReceiver::class.java.name, firstIntent.component?.className)
        assertEquals(first.taskId to first.generation, StopTaskAction.parse(firstIntent))
        val firstAction = action(first)
        assertTrue(firstAction.isImmutable)
        assertNotEquals(firstAction, action(second))
        assertNotEquals(firstAction, action(next))
    }

    @Test
    fun taskNotificationShowsIdentityAndProvidesOneTargetedStopAction() {
        val first = duty("first", 1)
        val factory = TaskNotificationFactory(context)
        factory.createChannel()
        val notification = factory.task(first, RuntimeState.RUNNING)
        assertTrue(notification.extras.getCharSequence("android.text").toString().contains("first"))
        assertEquals(1, notification.actions.size)
        assertTrue(notification.actions.single().actionIntent.isImmutable)
        assertEquals(context.getString(R.string.background_state_waiting), context.getString(RuntimeState.WAITING_APPROVAL.label()))
    }

    @Test
    fun manifestKeepsComponentsPrivateAndUsesOnlyDataSyncType() {
        val pm = context.packageManager
        val service = pm.getServiceInfo(android.content.ComponentName(context, RuntimeForegroundService::class.java), 0)
        val receiver = pm.getReceiverInfo(android.content.ComponentName(context, StopTaskReceiver::class.java), 0)
        assertFalse(service.exported)
        assertFalse(receiver.exported)
        assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, service.foregroundServiceType)
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission("android.permission.FOREGROUND_SERVICE_DATA_SYNC"))
    }

    private fun action(duty: ForegroundDuty): PendingIntent = PendingIntent.getBroadcast(
        context, 0, StopTaskAction.intent(context, duty), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun duty(id: String, generation: Long): ForegroundDuty =
        ForegroundDuty(TaskId.parse(id), generation, null, ForegroundPurpose.DATA_SYNC)
}
