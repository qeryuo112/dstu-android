package com.deepstudent.features.environments.backend

import com.deepstudent.features.environments.api.PackageKind
import com.deepstudent.features.environments.api.PackageRecipeState
import com.deepstudent.features.environments.api.PackageRecipeStatus
import com.deepstudent.features.environments.api.PackageReceipt

data class PipRequirement(
    val name: String,
    val version: String,
)

data class PackageRecipe(
    val id: String,
    val title: String,
    val kind: PackageKind,
    val aptPackages: List<String> = emptyList(),
    val pipRequirements: List<PipRequirement> = emptyList(),
    val venvPath: String? = null,
    val minimumFreeBytes: Long = 256L * 1024L * 1024L,
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9-]{1,40}"))) { "invalid recipe id" }
        require(aptPackages.all { it.matches(Regex("[a-z0-9][a-z0-9+.-]*")) }) {
            "invalid apt package name"
        }
        require(pipRequirements.all {
            it.name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")) &&
                it.version.matches(Regex("[A-Za-z0-9][A-Za-z0-9.+!_-]*"))
        }) { "invalid pip requirement" }
        require(kind != PackageKind.VENV_PIP ||
            venvPath?.matches(Regex("/root/(?:[A-Za-z0-9_-][A-Za-z0-9._-]*/)*[A-Za-z0-9_.-]+")) == true &&
            venvPath.split('/').none { it == "." || it == ".." }) {
            "venv path must be absolute below /root"
        }
        require(aptPackages.isNotEmpty()) { "recipe must declare apt dependencies" }
        require(kind != PackageKind.APT || pipRequirements.isEmpty()) { "apt recipe cannot declare pip requirements" }
        require(kind != PackageKind.VENV_PIP || pipRequirements.isNotEmpty()) { "venv recipe must pin requirements" }
        require(aptPackages.distinct().size == aptPackages.size) { "duplicate apt dependency" }
        require(pipRequirements.distinctBy { it.name }.size == pipRequirements.size) { "duplicate pip requirement" }
        require(minimumFreeBytes > 0) { "invalid package free-space budget" }
    }
}

object PackageRecipeCatalog {
    val Git = PackageRecipe(
        id = "git",
        title = "Git",
        kind = PackageKind.APT,
        aptPackages = listOf("git"),
    )

    val ProjectVenv = PackageRecipe(
        id = "project-venv",
        title = "Project Python venv",
        kind = PackageKind.VENV_PIP,
        aptPackages = listOf("python3-venv", "python3-pip"),
        pipRequirements = listOf(PipRequirement("requests", "2.32.3")),
        venvPath = "/root/projects/dstu/.venv",
    )

    val all: List<PackageRecipe> = listOf(Git, ProjectVenv)

    fun require(id: String): PackageRecipe =
        all.firstOrNull { it.id == id } ?: throw IllegalArgumentException("unknown package recipe")

    fun states(
        receipts: Map<String, PackageReceipt>,
        pendingRecipeId: String?,
        installing: Boolean,
        failed: Boolean,
        available: Boolean = true,
    ): List<PackageRecipeState> = all.map { recipe ->
        val receipt = receipts[recipe.id]
        val status = when {
            pendingRecipeId == recipe.id && installing -> PackageRecipeStatus.INSTALLING
            pendingRecipeId == recipe.id && failed -> PackageRecipeStatus.FAILED
            receipt != null -> PackageRecipeStatus.INSTALLED
            !available -> PackageRecipeStatus.NOT_INSTALLED
            else -> PackageRecipeStatus.READY_TO_INSTALL
        }
        PackageRecipeState(recipe.id, recipe.title, recipe.kind, status, receipt?.versions.orEmpty())
    }
}
