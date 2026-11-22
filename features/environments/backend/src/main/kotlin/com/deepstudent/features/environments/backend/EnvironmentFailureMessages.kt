package com.deepstudent.features.environments.backend

import com.deepstudent.features.environments.api.EnvironmentFailureCode
import com.deepstudent.features.environments.api.EnvironmentCommandException
import com.deepstudent.features.environments.api.EnvironmentFailure
import com.deepstudent.features.environments.api.EnvironmentPhase
import com.deepstudent.features.environments.api.EnvironmentProgress
import com.deepstudent.features.environments.api.EnvironmentStage
import com.deepstudent.features.environments.api.RootfsManifest

internal object EnvironmentFailureMessages {
    private val values = mapOf(
        EnvironmentFailureCode.BUSY to "another environment operation is running",
        EnvironmentFailureCode.INVALID_ARCHIVE to "rootfs archive is invalid",
        EnvironmentFailureCode.HASH_MISMATCH to "rootfs archive hash does not match the manifest",
        EnvironmentFailureCode.LOW_SPACE to "not enough free space for this environment",
        EnvironmentFailureCode.PATH_ESCAPE to "rootfs archive contains an escaping path",
        EnvironmentFailureCode.LINK_ESCAPE to "rootfs archive contains an escaping link",
        EnvironmentFailureCode.ENTRY_LIMIT to "rootfs archive exceeds the frozen entry budget",
        EnvironmentFailureCode.ARCHIVE_MISSING to "verified rootfs archive is unavailable",
        EnvironmentFailureCode.PROOT_UNAVAILABLE to "PRoot runtime is unavailable",
        EnvironmentFailureCode.HEALTH_CHECK_FAILED to "rootfs health probe failed",
        EnvironmentFailureCode.PACKAGE_INSTALL_FAILED to "package installation failed",
        EnvironmentFailureCode.INTERRUPTED to "environment operation was interrupted",
        EnvironmentFailureCode.CANCELLED to "environment operation was cancelled",
        EnvironmentFailureCode.CORRUPT_STATE to "environment state is corrupt",
        EnvironmentFailureCode.UNKNOWN to "environment operation failed",
    )

    fun forCode(code: EnvironmentFailureCode): String = values[code] ?: values.getValue(
        EnvironmentFailureCode.UNKNOWN,
    )
}

internal object EnvironmentPolicy {
    fun requireSpace(checker: EnvironmentSpaceChecker, requiredBytes: Long) {
        val available = checker.availableBytes()
        EnvironmentLog.event("space-check", "required=$requiredBytes available=$available")
        if (available < requiredBytes) {
            fail(
                EnvironmentFailureCode.LOW_SPACE,
                "not enough free space: required=$requiredBytes available=$available",
            )
        }
    }

    fun requireHealthy(manifest: RootfsManifest, observation: RootfsHealthObservation) {
        if (observation.matches(manifest)) return
        fail(
            EnvironmentFailureCode.HEALTH_CHECK_FAILED,
            "rootfs health probe did not match the manifest: " +
                "os=${observation.osId}/${observation.osVersion} " +
                "bash=${observation.hasBash} apt=${observation.hasApt} " +
                "python=${observation.hasPython}",
        )
    }

    fun initialExtraction(manifest: RootfsManifest): EnvironmentProgress = EnvironmentProgress(
        stage = EnvironmentStage.EXTRACT,
        totalEntries = manifest.entryCount,
        totalBytes = manifest.extractionBytesBudget,
    )

    private fun RootfsHealthObservation.matches(manifest: RootfsManifest): Boolean =
        osId == manifest.osId && osVersion == manifest.osVersion && hasBash && hasApt && hasPython

    private fun fail(code: EnvironmentFailureCode, message: String): Nothing =
        throw EnvironmentCommandException(EnvironmentFailure(code, message, recoverable = true))
}
