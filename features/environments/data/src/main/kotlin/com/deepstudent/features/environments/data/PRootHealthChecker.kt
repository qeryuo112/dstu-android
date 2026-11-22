package com.deepstudent.features.environments.data

import com.deepstudent.engines.proot.PRootProcessRunner
import com.deepstudent.engines.proot.PRootRunRequest
import com.deepstudent.engines.proot.PRootRuntimePaths
import com.deepstudent.features.environments.api.EnvironmentCommandException
import com.deepstudent.features.environments.api.EnvironmentFailure
import com.deepstudent.features.environments.api.EnvironmentFailureCode
import com.deepstudent.features.environments.api.RootfsManifest
import com.deepstudent.features.environments.backend.RootfsHealthChecker
import com.deepstudent.features.environments.backend.RootfsHealthObservation
import java.io.File
import kotlinx.coroutines.CancellationException

internal class PRootHealthChecker(
    private val paths: EnvironmentPaths,
    private val runtime: PRootRuntimePaths?,
) : RootfsHealthChecker {
    override suspend fun check(manifest: RootfsManifest, stagingToken: String): RootfsHealthObservation {
        val runtime = runtime ?: throw EnvironmentCommandException(
            EnvironmentFailure(
                EnvironmentFailureCode.PROOT_UNAVAILABLE,
                "PRoot artifact source/license is unresolved; execution is fail-closed",
                recoverable = true,
            ),
        )
        EnvironmentBindings.ensureResolvConf(paths)
        val root = paths.stagingRoot(stagingToken)
        val result = runCatching {
            PRootProcessRunner(runtime).run(
                PRootRunRequest(
                    rootfs = root,
                    script = HEALTH_SCRIPT,
                    timeoutMillis = 60_000,
                    bindMounts = EnvironmentBindings.binds(paths),
                ),
            )
        }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            EnvironmentDataLog.failure(
                "proroot-health-start-failed",
                failure,
            )
            throw EnvironmentCommandException(
                EnvironmentFailure(
                    EnvironmentFailureCode.HEALTH_CHECK_FAILED,
                    "PRoot health probe could not start (${failure.javaClass.simpleName})",
                    recoverable = true,
                ),
                failure,
            )
        }
        val fields = result.output.lineSequence()
            .mapNotNull { line -> line.takeIf { it.startsWith("DSTU_") } }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size >= 2) parts[0].removePrefix("DSTU_") to parts[1] else null
            }
            .toMap()
        if (result.exitCode != 0 || !result.output.contains("DSTU_HEALTH_OK")) {
            throw EnvironmentCommandException(
                EnvironmentFailure(
                    EnvironmentFailureCode.HEALTH_CHECK_FAILED,
                    "rootfs health probe exited with code ${result.exitCode}",
                    recoverable = true,
                ),
            )
        }
        return RootfsHealthObservation(
            osId = fields["OS_ID"].orEmpty(),
            osVersion = fields["OS_VERSION"].orEmpty(),
            hasBash = fields.containsKey("BASH"),
            hasApt = fields.containsKey("APT"),
            hasPython = fields.containsKey("PYTHON"),
        )
    }

    private companion object {
        val HEALTH_SCRIPT = """
            set -eu
            . /etc/os-release
            printf 'DSTU_OS_ID\t%s\n' "${'$'}ID"
            printf 'DSTU_OS_VERSION\t%s\n' "${'$'}VERSION_ID"
            test -x /bin/bash
            printf 'DSTU_BASH\tok\n'
            test -x /usr/bin/apt-get
            printf 'DSTU_APT\tok\n'
            test -x /usr/bin/python3
            printf 'DSTU_PYTHON\tok\n'
            printf 'DSTU_HEALTH_OK\n'
        """.trimIndent()
    }
}
