package com.deepstudent.features.environments.backend

import com.deepstudent.features.environments.api.EnvironmentCommandException
import com.deepstudent.features.environments.api.EnvironmentFailure
import com.deepstudent.features.environments.api.EnvironmentFailureCode
import com.deepstudent.features.environments.api.EnvironmentPhase
import com.deepstudent.features.environments.api.EnvironmentProgress
import com.deepstudent.features.environments.api.EnvironmentStage
import com.deepstudent.features.environments.api.PackageReceipt
import java.time.Instant
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal class PackageInstallation(private val ports: EnvironmentPorts) {
    suspend fun run(tx: EnvironmentTransaction, recipe: PackageRecipe) {
        val journal = tx.journal
        val active = journal.activeGeneration ?: fail(EnvironmentFailureCode.CORRUPT_STATE)
        val manifest = journal.rootfsId?.let(com.deepstudent.features.environments.api.EnvironmentCatalog::find)
            ?: fail(EnvironmentFailureCode.CORRUPT_STATE)
        if (ports.activator.currentActive(manifest) != active) fail(EnvironmentFailureCode.CORRUPT_STATE)
        EnvironmentPolicy.requireSpace(ports.spaceChecker, recipe.minimumFreeBytes)
        coroutineContext.ensureActive()
        tx.progress(EnvironmentPhase.INSTALLING_PACKAGE, EnvironmentProgress(stage = EnvironmentStage.APT))
        val receipt = ports.packageInstaller.install(recipe, active) { stage, _ ->
            tx.progress(EnvironmentPhase.INSTALLING_PACKAGE, EnvironmentProgress(stage = stage))
        }
        coroutineContext.ensureActive()
        tx.progress(EnvironmentPhase.INSTALLING_PACKAGE, EnvironmentProgress(stage = EnvironmentStage.PROBE))
        PackageReceiptPolicy.requireValid(recipe, receipt)
        tx.commit(tx.journal.copy(
            receipts = tx.journal.receipts + (recipe.id to receipt), pendingRecipeId = null, failure = null,
        ))
        tx.progress(EnvironmentPhase.READY, EnvironmentProgress(stage = EnvironmentStage.COMPLETE))
        EnvironmentLog.event("package-receipt-committed", "recipe=${recipe.id} versions=${receipt.versions.size}")
    }

    private fun fail(code: EnvironmentFailureCode): Nothing = throw EnvironmentCommandException(
        EnvironmentFailure(code, EnvironmentFailureMessages.forCode(code), code != EnvironmentFailureCode.CORRUPT_STATE),
    )
}

/** An exit code or success marker is not an installation receipt. Require measured versions. */
internal object PackageReceiptPolicy {
    fun requireValid(recipe: PackageRecipe, receipt: PackageReceipt) {
        require(receipt.recipeId == recipe.id) { "package receipt identity mismatch" }
        Instant.parse(receipt.installedAt)
        val versions = receipt.versions
        require(versions.isNotEmpty()) { "package version probe returned no versions" }
        require(versions.distinctBy { it.name to it.source }.size == versions.size) { "duplicate package version" }
        require(versions.all { it.version.isNotBlank() && it.version.none(Char::isISOControl) }) { "invalid package version" }
        recipe.aptPackages.forEach { name ->
            require(versions.any { it.name == name && it.source == "apt" }) { "missing apt version" }
        }
        recipe.pipRequirements.forEach { requirement ->
            require(versions.any {
                it.name == requirement.name && it.source == "venv" && it.version == requirement.version
            }) { "missing pinned venv version" }
        }
        if (recipe.kind == com.deepstudent.features.environments.api.PackageKind.VENV_PIP) {
            require(versions.any { it.name == "python3" && it.source == "apt" }) { "missing Python version" }
        }
    }
}
