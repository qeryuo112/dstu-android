package com.deepstudent.features.environments.data

import com.deepstudent.core.common.time.SystemWallClock
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.engines.proot.PRootProcessRunner
import com.deepstudent.engines.proot.PRootRunRequest
import com.deepstudent.engines.proot.PRootRuntimePaths
import com.deepstudent.features.environments.api.EnvironmentCommandException
import com.deepstudent.features.environments.api.EnvironmentFailure
import com.deepstudent.features.environments.api.EnvironmentFailureCode
import com.deepstudent.features.environments.api.EnvironmentStage
import com.deepstudent.features.environments.api.InstalledVersion
import com.deepstudent.features.environments.api.PackageKind
import com.deepstudent.features.environments.api.PackageReceipt
import com.deepstudent.features.environments.backend.EnvironmentPackageInstaller
import com.deepstudent.features.environments.backend.PackageRecipe
import java.io.File
import java.time.Instant

internal class PRootPackageInstaller(
    private val paths: EnvironmentPaths,
    private val runtime: PRootRuntimePaths?,
    private val clock: WallClock = SystemWallClock,
) : EnvironmentPackageInstaller {
    override suspend fun install(
        recipe: PackageRecipe,
        activeGeneration: String,
        onStage: (EnvironmentStage, String) -> Unit,
    ): PackageReceipt {
        val runtime = requireRuntime()
        val root = requireActiveRoot(activeGeneration)
        onStage(EnvironmentStage.APT, "Installing ${recipe.title}")
        val result = PRootProcessRunner(runtime).run(
            PRootRunRequest(
                rootfs = root,
                script = PackageInstallScript.render(recipe),
                timeoutMillis = 45L * 60L * 1000L,
                bindMounts = EnvironmentBindings.binds(paths),
                maxOutputBytes = 512 * 1024,
            ),
        )
        requireSuccess(recipe, result.exitCode, result.output)
        onStage(EnvironmentStage.PROBE, "Probing ${recipe.title}")
        val versions = requireVersions(result.output, recipe)
        EnvironmentDataLog.event(
            "package-installed",
            "recipe=${recipe.id} versions=${versions.joinToString(",") { it.name }}",
        )
        return PackageReceipt(recipe.id, clock.now().toString(), versions)
    }

    private fun requireRuntime(): PRootRuntimePaths = runtime ?: throw EnvironmentCommandException(
        EnvironmentFailure(
            EnvironmentFailureCode.PROOT_UNAVAILABLE,
            "PRoot artifact source/license is unresolved; execution is fail-closed",
            recoverable = true,
        ),
    )

    private fun requireActiveRoot(activeGeneration: String): File {
        val root = paths.generationRoot(activeGeneration)
        if (root.isDirectory) return root
        throw EnvironmentCommandException(
            EnvironmentFailure(
                EnvironmentFailureCode.CORRUPT_STATE,
                "active rootfs is unavailable",
                recoverable = true,
            ),
        )
    }

    private fun requireSuccess(recipe: PackageRecipe, exitCode: Int, output: String) {
        if (exitCode == 0) return
        EnvironmentDataLog.failure(
            "package-command-failed",
            IllegalStateException("PRoot package command"),
            "recipe=${recipe.id} exit=$exitCode capturedBytes=${output.toByteArray().size}",
        )
        throw EnvironmentCommandException(
            EnvironmentFailure(
                EnvironmentFailureCode.PACKAGE_INSTALL_FAILED,
                "package installation exited with code $exitCode",
                recoverable = true,
            ),
        )
    }

    private fun requireVersions(output: String, recipe: PackageRecipe): List<InstalledVersion> {
        val versions = parseVersions(output, recipe)
        val expected = recipe.aptPackages.map { it to "apt" } +
            recipe.pipRequirements.map { it.name to "venv" }
        if (expected.all { required -> versions.any { it.name to it.source == required } }) return versions
        throw EnvironmentCommandException(
            EnvironmentFailure(
                EnvironmentFailureCode.PACKAGE_INSTALL_FAILED,
                "package installation did not produce version evidence",
                recoverable = true,
            ),
        )
    }

    private fun parseVersions(output: String, recipe: PackageRecipe): List<InstalledVersion> {
        val rows = output.lineSequence()
            .filter { it.startsWith("DSTU_VERSION\t") }
            .map { line -> line.split('\t') }
            .filter { parts -> parts.size >= 3 && parts[1].isNotBlank() && parts[2].isNotBlank() }
            .map { parts ->
                val source = if (parts[1] in recipe.aptPackages || parts[1] == "python3") "apt" else "venv"
                InstalledVersion(parts[1], parts[2], source)
            }
            .toList()
        return rows.distinctBy { it.name to it.source }
    }

}
