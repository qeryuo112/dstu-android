package com.deepstudent.platform.execution

import android.os.ParcelFileDescriptor
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.lang.reflect.Modifier
import rikka.shizuku.Shizuku
import org.junit.Test

/**
 * Locks the 13.1.5 AIDL surface compiled into the module. If a dependency bump
 * changes `newProcess`, the UID probe or the descriptor getters, this test fails
 * before any device test can silently exercise a different contract.
 */
class ShizukuAidlContractTest {
    @Test
    fun clientBinderAccessorIsPublicButServiceAccessorIsNot() {
        assertEquals(android.os.IBinder::class.java, Shizuku::class.java.getMethod("getBinder").returnType)
        assertTrue(Modifier.isProtected(Shizuku::class.java.getDeclaredMethod("requireService").modifiers))
        assertTrue(Shizuku::class.java.methods.none { it.name == "getService" })
    }

    @Test
    fun fixedServiceDescriptorAndTransactionNumbersMatchTheSdkWireContract() {
        val stub = IShizukuService.Stub::class.java
        val descriptor = stub.getDeclaredField("DESCRIPTOR").apply { isAccessible = true }.get(null)
        assertEquals("moe.shizuku.server.IShizukuService", descriptor)
        val expected = mapOf(
            "getVersion" to 3,
            "getUid" to 4,
            "newProcess" to 8,
            "requestPermission" to 15,
            "checkSelfPermission" to 16,
            "attachApplication" to 18,
        )
        expected.forEach { (name, transaction) ->
            val field = stub.getDeclaredField("TRANSACTION_$name").apply { isAccessible = true }
            assertEquals(transaction, field.getInt(null))
        }
    }

    @Test
    fun serviceExposesVersionUidPermissionAndNewProcess() {
        val newProcess = IShizukuService::class.java.getMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        assertEquals(IRemoteProcess::class.java, newProcess.returnType)
        assertEquals(
            Int::class.javaPrimitiveType,
            IShizukuService::class.java.getMethod("getUid").returnType,
        )
        assertEquals(
            Int::class.javaPrimitiveType,
            IShizukuService::class.java.getMethod("getVersion").returnType,
        )
        assertEquals(
            Boolean::class.javaPrimitiveType,
            IShizukuService::class.java.getMethod("checkSelfPermission").returnType,
        )
    }

    @Test
    fun remoteProcessExposesThreeDescriptorsWaitAndDestroy() {
        assertEquals(
            ParcelFileDescriptor::class.java,
            IRemoteProcess::class.java.getMethod("getOutputStream").returnType,
        )
        assertEquals(
            ParcelFileDescriptor::class.java,
            IRemoteProcess::class.java.getMethod("getInputStream").returnType,
        )
        assertEquals(
            ParcelFileDescriptor::class.java,
            IRemoteProcess::class.java.getMethod("getErrorStream").returnType,
        )
        assertEquals(
            Int::class.javaPrimitiveType,
            IRemoteProcess::class.java.getMethod("waitFor").returnType,
        )
        IRemoteProcess::class.java.getMethod("destroy")
        assertEquals(
            Boolean::class.javaPrimitiveType,
            IRemoteProcess::class.java.getMethod("alive").returnType,
        )
        val timeout = IRemoteProcess::class.java.getMethod(
            "waitForTimeout",
            Long::class.javaPrimitiveType,
            String::class.java,
        )
        assertEquals(Boolean::class.javaPrimitiveType, timeout.returnType)
    }
}
