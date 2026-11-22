package com.deepstudent.platform.execution

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deepstudent.services.execution.api.ShizukuConnectionState
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises fixed AIDL parcels without a real server or changes to SDK/global authorization. */
@RunWith(AndroidJUnit4::class)
class ShizukuSdkHandshakeDeviceTest {
    @get:Rule
    val foreground = ExecutionForegroundRule()

    @Test
    fun preGrantSdkIdentityEnablesRequestWithoutProtectedMetadataTransactions() {
        val server = PermissionGatedBinder()
        val binding = sdkBinding(server)
        assertEquals(2000, binding.backendUid)
        assertEquals(13, binding.serviceVersion)
        assertEquals(0, server.metadataCalls.get())
        assertFalse(binding.transport.checkSelfPermission())
        val status = boundShizukuStatus(binding, null, false, false, null)
        assertEquals(ShizukuConnectionState.PERMISSION_REQUIRED, status.state)
        assertTrue(status.canRequestPermission)
        assertFalse(status.canExecute)
        assertEquals(0, server.metadataCalls.get())
    }

    @Test
    fun freshGrantAndAuthorizedUidProbeAreNotSdkPermissionCacheReads() {
        val server = PermissionGatedBinder()
        val binding = sdkBinding(server)
        assertFalse(binding.transport.checkSelfPermission())
        server.granted = true
        assertTrue(binding.transport.checkSelfPermission())
        assertEquals(2000, binding.transport.uid())
        assertEquals(1, server.metadataCalls.get())
        server.granted = false
        assertFalse(binding.transport.checkSelfPermission())
        assertEquals(3, server.permissionChecks.get())
        assertTrue(runCatching { binding.transport.uid() }.exceptionOrNull() is SecurityException)
    }

    @Test
    fun missingHandshakeMetadataCannotInventAnIdentityOrBecomeReady() {
        val server = PermissionGatedBinder()
        val records = mutableListOf<ExecutionLogRecord>()
        val result = runCatching {
            AndroidShizukuAccess.binding(server, { records += it }, { -1 }, { 13 }, { server })
        }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(0, server.metadataCalls.get())
        val failure = records.filterIsInstance<ExecutionFailureLogEvent>().single()
        assertEquals(ExecutionStage.SDK_UID, failure.stage)
        assertEquals(IllegalStateException::class.java.name, failure.exceptionClass)
    }

    @Test
    fun sdkReplacementCannotKeepTheOldBinderBindingAlive() {
        val first = PermissionGatedBinder()
        var current: IBinder = first
        val binding = AndroidShizukuAccess.binding(first, {}, { 2000 }, { 13 }, { current })
        assertTrue(binding.isAlive())
        current = PermissionGatedBinder()
        assertFalse(binding.isAlive())
    }

    private fun sdkBinding(server: PermissionGatedBinder) =
        AndroidShizukuAccess.binding(server, {}, { 2000 }, { 13 }, { server })

    private class PermissionGatedBinder : Binder() {
        var granted = false
        val metadataCalls = AtomicInteger()
        val permissionChecks = AtomicInteger()

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                checkNotNull(reply).writeString(DESCRIPTOR)
                return true
            }
            data.enforceInterface(DESCRIPTOR)
            val result = when (code) {
                3, 4 -> {
                    metadataCalls.incrementAndGet()
                    if (!granted) throw SecurityException("private server exception body")
                    if (code == 4) 2000 else 13
                }
                16 -> {
                    permissionChecks.incrementAndGet()
                    if (granted) 1 else 0
                }
                else -> error("Unexpected transaction in the 13.1.5 contract test")
            }
            checkNotNull(reply).writeNoException()
            reply.writeInt(result)
            return true
        }

        private companion object {
            const val DESCRIPTOR = "moe.shizuku.server.IShizukuService"
        }
    }
}
