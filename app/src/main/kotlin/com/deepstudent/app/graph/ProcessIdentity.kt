package com.deepstudent.app.graph

enum class ProcessRole {
    MAIN,
    ENGINE,
    ISOLATED,
    AUXILIARY,
}

enum class ProcessNameSource {
    APPLICATION_API,
    PROC_CMDLINE,
    UNAVAILABLE,
}

data class ProcessIdentity(
    val processName: String?,
    val role: ProcessRole,
    val source: ProcessNameSource,
    val isIsolatedUid: Boolean = false,
) {
    val hasKnownName: Boolean
        get() = !processName.isNullOrBlank()
}

/**
 * Pure process-name classifier. Android process naming and the isolated UID
 * rule are applied by [AndroidProcessIdentityResolver].
 */
object ProcessClassifier {
    const val ENGINE_SUFFIX = ":engine"
    const val ISOLATED_SUFFIX = ":isolated"
    private const val DEFAULT_ISOLATED_NAME = "isolated"

    fun classify(
        applicationId: String,
        processName: String?,
        source: ProcessNameSource = ProcessNameSource.UNAVAILABLE,
    ): ProcessIdentity {
        val normalized = processName?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ProcessIdentity(
                processName = null,
                role = ProcessRole.AUXILIARY,
                source = ProcessNameSource.UNAVAILABLE,
            )

        val role = roleFor(applicationId, normalized)
        return ProcessIdentity(normalized, role, source)
    }

    private fun roleFor(applicationId: String, processName: String): ProcessRole {
        if (processName == applicationId) return ProcessRole.MAIN
        val suffix = processName.removePrefix(applicationId)
        return when {
            suffix == ENGINE_SUFFIX -> ProcessRole.ENGINE
            suffix == ISOLATED_SUFFIX -> ProcessRole.ISOLATED
            processName == ISOLATED_SUFFIX -> ProcessRole.ISOLATED
            processName == DEFAULT_ISOLATED_NAME -> ProcessRole.ISOLATED
            // Android 16 appends ":<service class>" to isolated service process names.
            processName.startsWith(applicationId + ISOLATED_SUFFIX + ":") -> ProcessRole.ISOLATED
            else -> ProcessRole.AUXILIARY
        }
    }
}
