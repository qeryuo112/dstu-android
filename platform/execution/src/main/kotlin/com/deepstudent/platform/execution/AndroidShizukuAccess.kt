package com.deepstudent.platform.execution

import android.os.DeadObjectException
import android.os.IBinder
import java.util.UUID
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/**
 * 13.1.5 bindApplication fills the SDK UID/version before its received callback.
 * The service's raw getUid/getVersion enforce authorization: invoking them here
 * prevents an ungranted client from reaching the official permission request.
 * Metadata is valid only for this exact SDK-selected binder (Shizuku OR Sui).
 */
internal object AndroidShizukuAccess {
    fun binding(
        binder: IBinder,
        log: ExecutionLogSink,
        sdkUid: () -> Int = Shizuku::getUid,
        sdkVersion: () -> Int = Shizuku::getVersion,
        currentBinder: () -> IBinder? = Shizuku::getBinder,
    ): ShizukuBinding {
        val uid = log.atStage(ExecutionStage.SDK_UID) { sdkUid().also { check(it >= 0) } }
        val version = log.atStage(ExecutionStage.SDK_VERSION) { sdkVersion().also { check(it > 0) } }
        if (currentBinder() !== binder || !binder.isBinderAlive) throw DeadObjectException()
        val service = checkNotNull(IShizukuService.Stub.asInterface(binder))
        log.emit(ExecutionLogEvent("sdk_handshake_binding", backendUid = uid))
        return ShizukuBinding(
            bindingId = UUID.randomUUID().toString(),
            backendUid = uid,
            serviceVersion = version,
            isAlive = { currentBinder() === binder && binder.isBinderAlive && binder.pingBinder() },
            transport = AndroidShizukuTransport(service, log),
        )
    }
}
