package com.deepstudent.features.plugins.api

enum class PluginTrust(val wire: String) {
    UNTRUSTED("untrusted"),
    RESTRICTED("restricted"),
    FULL("full"),
}

data class LocalPluginInstallState(
    val ownerId: PluginOwnerId,
    val installed: Boolean,
    val enabled: Boolean,
    val trusted: Boolean,
    val trust: PluginTrust,
    val fullTrust: Boolean,
) {
    val local: Boolean get() = true
    val synced: Boolean get() = false

    init {
        if (!trusted && (trust != PluginTrust.UNTRUSTED || fullTrust)) {
            pluginError("INVALID_TRUST_STATE", "untrusted plugins must not carry local authorization")
        }
        if (fullTrust && (!installed || !enabled || !trusted || trust != PluginTrust.FULL)) {
            pluginError("INVALID_TRUST_STATE", "full trust requires local install, enable, and trust")
        }
    }
}

data class LocalPluginUninstall(
    val installState: LocalPluginInstallState,
    val deleteData: Boolean,
)

interface LocalPluginPolicy {
    fun inspect(): LocalPluginInstallState

    fun setLocalInstall(
        installed: Boolean? = null,
        enabled: Boolean? = null,
        trusted: Boolean? = null,
        trust: PluginTrust? = null,
    ): LocalPluginInstallState

    fun uninstall(authorized: Boolean, deleteData: Boolean = false): LocalPluginUninstall
}

data class PluginScheduleState(
    val since: Double,
    val last: Double?,
) {
    init {
        if (!since.isFinite() || (last != null && !last.isFinite())) {
            pluginError("INVALID_SCHEDULE", "plugin schedule timestamps must be finite")
        }
    }
}

interface PluginLocalServices {
    fun getSecret(key: String): String?

    fun setSecret(key: String, value: String): LocalPluginWrite

    fun deleteSecret(key: String): Boolean

    fun readSchedule(jobId: String): PluginScheduleState?

    fun writeSchedule(jobId: String, state: PluginScheduleState): LocalPluginWrite

    fun deleteSchedule(jobId: String): Boolean
}

data class LocalPluginWrite(
    val key: String,
    val local: Boolean = true,
    val portable: Boolean = false,
)

object PluginLocalKeys {
    const val SECRET_PREFIX = "secret.plugin."
    const val SCHEDULE_PREFIX = "plugin.schedule."

    private val segment = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    fun secretKey(ownerId: PluginOwnerId, key: String): String {
        if (!segment.matches(key)) pluginError("INVALID_PLUGIN_SECRET_KEY", "invalid plugin secret key")
        return "$SECRET_PREFIX${ownerId.value}.$key"
    }

    fun scheduleKey(ownerId: PluginOwnerId, jobId: String): String {
        if (!segment.matches(jobId)) pluginError("INVALID_PLUGIN_SCHEDULE", "invalid plugin schedule id")
        return "$SCHEDULE_PREFIX${ownerId.value}.$jobId"
    }
}

data class PluginContentDescriptor(
    val ownerId: PluginOwnerId,
    val packageVersion: PluginPackageVersion,
    val sourceHash: PluginSourceHash,
    val schemaVersion: Int,
)

data class PluginContentInspection(
    val content: PluginContentDescriptor,
    val install: LocalPluginInstallState,
    val executes: Boolean = false,
)

object PluginContentPolicy {
    /**
     * Content provenance never grants local install, enable, trust, or execution.
     * Only the injected local policy is observed.
     */
    fun inspect(content: PluginContentDescriptor, policy: LocalPluginPolicy): PluginContentInspection =
        PluginContentInspection(
            content = content,
            install = policy.inspect(),
            executes = false,
        )
}

data class PluginUninstallOutcome(
    val installState: LocalPluginInstallState,
    val retainedSyncedData: Boolean,
    val dataTombstones: Int?,
    val executes: Boolean = false,
)

object PluginUninstall {
    fun plan(
        policy: LocalPluginPolicy,
        storeOwner: PluginOwnerId,
        authorized: Boolean,
        deleteData: Boolean = false,
    ): PluginUninstallOutcome {
        if (policy.inspect().ownerId != storeOwner) {
            pluginError("PLUGIN_OWNER_MISMATCH", "store and policy owner must match")
        }
        val result = policy.uninstall(authorized = authorized, deleteData = deleteData)
        if (!result.installState.installed &&
            (result.installState.enabled || result.installState.trusted || result.installState.fullTrust)
        ) {
            pluginError("INVALID_TRUST_STATE", "uninstall must clear local authorization")
        }
        return PluginUninstallOutcome(
            installState = result.installState,
            retainedSyncedData = !result.deleteData,
            dataTombstones = if (result.deleteData) null else 0,
        )
    }
}
