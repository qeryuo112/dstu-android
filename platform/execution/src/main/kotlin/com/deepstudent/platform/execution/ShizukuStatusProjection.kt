package com.deepstudent.platform.execution

import com.deepstudent.services.execution.api.ShizukuConnectionState
import com.deepstudent.services.execution.api.ShizukuStatus

/** Pure connection/permission projection. No listeners, cached grants or lifecycle work. */
internal fun boundShizukuStatus(
    binding: ShizukuBinding,
    installedVersion: String?,
    granted: Boolean,
    everAuthorized: Boolean,
    observedUid: Int?,
): ShizukuStatus {
    val state = when {
        binding.backendUid != 0 && binding.backendUid != 2000 -> ShizukuConnectionState.UNTRUSTED_BACKEND
        granted -> ShizukuConnectionState.READY
        everAuthorized -> ShizukuConnectionState.REVOKED
        else -> ShizukuConnectionState.PERMISSION_REQUIRED
    }
    return ShizukuStatus(
        state = state,
        installedVersionName = installedVersion,
        serviceVersion = binding.serviceVersion,
        backendUid = binding.backendUid,
        observedUid = if (state == ShizukuConnectionState.READY) observedUid else null,
        diagnostic = state.failureCode(),
    )
}

internal fun unavailableShizukuStatus(installedVersion: String?, previous: ShizukuStatus): ShizukuStatus {
    val state = when {
        previous.state == ShizukuConnectionState.BINDER_DEAD -> ShizukuConnectionState.BINDER_DEAD
        installedVersion == null -> ShizukuConnectionState.NOT_INSTALLED
        else -> ShizukuConnectionState.NOT_RUNNING
    }
    return ShizukuStatus(
        state = state,
        installedVersionName = installedVersion,
        backendUid = previous.backendUid,
        diagnostic = state.failureCode(),
    )
}
