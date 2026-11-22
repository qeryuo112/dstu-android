package com.deepstudent.features.environments.api

import java.io.File
import java.io.InputStream
import kotlinx.coroutines.flow.StateFlow

@JvmInline
value class EnvironmentTaskId(val value: String) {
    init {
        require(value.matches(Regex("[A-Za-z0-9-]{8,80}"))) { "invalid environment task id" }
    }
}

data class RootfsArchiveSpec(
    val name: String,
    val sha256: String,
    val bytes: Long,
    val root: String,
    val entryCount: Int,
)

data class RootfsResourceBudget(
    val extractionBytes: Long,
    val minimumFreeBytes: Long,
)

data class RootfsPlatformIdentity(
    val osId: String,
    val osVersion: String,
)

data class RootfsManifest(
    val id: String,
    val archive: RootfsArchiveSpec,
    val budget: RootfsResourceBudget,
    val platform: RootfsPlatformIdentity,
) {
    val archiveName: String get() = archive.name
    val sha256: String get() = archive.sha256
    val archiveBytes: Long get() = archive.bytes
    val archiveRoot: String get() = archive.root
    val entryCount: Int get() = archive.entryCount
    val extractionBytesBudget: Long get() = budget.extractionBytes
    val minimumFreeBytes: Long get() = budget.minimumFreeBytes
    val osId: String get() = platform.osId
    val osVersion: String get() = platform.osVersion

    init {
        require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}"))) { "invalid rootfs id" }
        require(archiveName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.tar\\.xz"))) {
            "rootfs archive must be a tar.xz basename"
        }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "rootfs sha256 must be lowercase hex" }
        require(archiveBytes > 0) { "rootfs archive size must be positive" }
        require(archiveRoot.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) { "invalid archive root" }
        require(entryCount > 0) { "rootfs entry count must be positive" }
        require(extractionBytesBudget > 0) { "rootfs extraction budget must be positive" }
        require(minimumFreeBytes >= archiveBytes &&
            extractionBytesBudget <= minimumFreeBytes - archiveBytes) {
            "rootfs free-space budget must cover archive and extraction"
        }
        require(osId.isNotBlank() && osVersion.isNotBlank()) { "rootfs os identity is required" }
    }
}

object EnvironmentCatalog {
    /**
     * Frozen external Operit image. The archive is intentionally not copied into Git.
     * Measured tar headers contain 12,684 entries and 292,934,755 regular-file bytes.
     */
    val UbuntuNobleAarch64 = RootfsManifest(
        id = "ubuntu-noble-aarch64-pd-v4.18.0",
        archive = RootfsArchiveSpec(
            name = "ubuntu-noble-aarch64-pd-v4.18.0.tar.xz",
            sha256 = "91acaa786b8e2fbba56a9fd0f8a1188cee482b5c7baeed707b29ddaa9a294daa",
            bytes = 64_133_552,
            root = "ubuntu-noble-aarch64",
            entryCount = 12_684,
        ),
        budget = RootfsResourceBudget(
            extractionBytes = 310L * 1024L * 1024L,
            minimumFreeBytes = 512L * 1024L * 1024L,
        ),
        platform = RootfsPlatformIdentity("ubuntu", "24.04"),
    )

    fun find(id: String): RootfsManifest? =
        listOf(UbuntuNobleAarch64).firstOrNull { it.id == id }
}

enum class EnvironmentPhase {
    IDLE,
    VERIFYING,
    EXTRACTING,
    HEALTH_CHECKING,
    ACTIVATING,
    READY,
    INSTALLING_PACKAGE,
    FAILED,
    CANCELLED,
}

enum class EnvironmentStage {
    IDLE,
    ARCHIVE_VERIFY,
    ARCHIVE_COPY,
    EXTRACT,
    HEALTH_CHECK,
    ACTIVATE,
    APT,
    VENV,
    PROBE,
    COMPLETE,
}

data class EnvironmentProgress(
    val stage: EnvironmentStage = EnvironmentStage.IDLE,
    val completedEntries: Int = 0,
    val totalEntries: Int = 0,
    val processedBytes: Long = 0,
    val totalBytes: Long = 0,
)

enum class EnvironmentFailureCode {
    BUSY,
    INVALID_ARCHIVE,
    HASH_MISMATCH,
    LOW_SPACE,
    PATH_ESCAPE,
    LINK_ESCAPE,
    ENTRY_LIMIT,
    ARCHIVE_MISSING,
    PROOT_UNAVAILABLE,
    HEALTH_CHECK_FAILED,
    PACKAGE_INSTALL_FAILED,
    INTERRUPTED,
    CANCELLED,
    CORRUPT_STATE,
    UNKNOWN,
}

data class EnvironmentFailure(
    val code: EnvironmentFailureCode,
    val message: String,
    val recoverable: Boolean,
)

enum class PackageKind {
    APT,
    VENV_PIP,
}

enum class PackageRecipeStatus {
    NOT_INSTALLED,
    READY_TO_INSTALL,
    INSTALLING,
    INSTALLED,
    FAILED,
}

data class PackageRecipeState(
    val id: String,
    val title: String,
    val kind: PackageKind,
    val status: PackageRecipeStatus,
    val versions: List<InstalledVersion> = emptyList(),
)

data class InstalledVersion(
    val name: String,
    val version: String,
    val source: String,
)

data class PackageReceipt(
    val recipeId: String,
    val installedAt: String,
    val versions: List<InstalledVersion>,
)

data class EnvironmentRuntimeState(
    val taskId: EnvironmentTaskId? = null,
    val progress: EnvironmentProgress = EnvironmentProgress(),
    val recipes: List<PackageRecipeState> = emptyList(),
    val lastReceipt: PackageReceipt? = null,
    val failure: EnvironmentFailure? = null,
    val recoverable: Boolean = false,
)

data class EnvironmentSnapshot(
    val phase: EnvironmentPhase = EnvironmentPhase.IDLE,
    val rootfsId: String? = null,
    val rootfsSha256: String? = null,
    val activeGeneration: String? = null,
    val runtime: EnvironmentRuntimeState = EnvironmentRuntimeState(),
) {
    val taskId: EnvironmentTaskId? get() = runtime.taskId
    val progress: EnvironmentProgress get() = runtime.progress
    val recipes: List<PackageRecipeState> get() = runtime.recipes
    val lastReceipt: PackageReceipt? get() = runtime.lastReceipt
    val failure: EnvironmentFailure? get() = runtime.failure
    val recoverable: Boolean get() = runtime.recoverable
}

/** The adapter owns I/O. UI passes this handle without opening or inspecting it. */
fun interface RootfsArchiveSource {
    fun open(): InputStream

    companion object {
        fun fromFile(file: File): RootfsArchiveSource = RootfsArchiveSource { file.inputStream() }
    }
}

open class EnvironmentCommandException(
    val failure: EnvironmentFailure,
    cause: Throwable? = null,
) : RuntimeException(failure.message, cause)

interface EnvironmentCommands {
    suspend fun installRootfs(
        manifest: RootfsManifest,
        source: RootfsArchiveSource,
    ): EnvironmentTaskId

    suspend fun installPackage(recipeId: String): EnvironmentTaskId

    suspend fun retry(source: RootfsArchiveSource? = null): EnvironmentTaskId

    suspend fun cancel(taskId: EnvironmentTaskId)
}

interface EnvironmentQuery {
    val state: StateFlow<EnvironmentSnapshot>
}

interface EnvironmentManager : EnvironmentCommands, EnvironmentQuery
