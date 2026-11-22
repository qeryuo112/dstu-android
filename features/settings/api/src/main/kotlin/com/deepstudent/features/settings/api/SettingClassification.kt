package com.deepstudent.features.settings.api

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException

enum class SettingScope(val wire: String) {
    PORTABLE("portable"),
    LOCAL("local"),
    SECRET("secret"),
}

data class SettingClass(
    val key: String,
    val scope: SettingScope,
    val reason: String,
)

data class AuthorizationBoundary(
    val id: String,
    val scope: SettingScope,
    val exactKey: String? = null,
    val prefix: String? = null,
    val reason: String,
)

object SettingClassification {
    val PORTABLE_SYNCABLE_KEYS: List<String> = listOf(
        "pdf.settings",
        "pomodoro.settings",
        "portable.model.preferences.v1",
        "portable.tool.preferences.v1",
        "shortcuts.custom",
        "skill.ui.bundles",
        "skill.ui.favorites",
        "sync.include_plugins",
        "sync.include_skills",
        "theme",
        "theme_custom_color",
        "theme_palette",
        "translation.promptTemplates.v1",
    )

    val PORTABLE_KEYS: Set<String> = PORTABLE_SYNCABLE_KEYS.toSet() + setOf(
        "dstu-theme-custom-color",
        "dstu-theme-mode",
        "dstu-theme-palette",
    )

    const val THEME_VARIANT_KEY = "ds.ui.themeVariant"
    const val PLUGIN_SECRET_PREFIX = "secret.plugin."
    const val PLUGIN_SCHEDULE_PREFIX = "plugin.schedule."

    val AUTHORIZATION_BOUNDARIES: List<AuthorizationBoundary> = listOf(
        AuthorizationBoundary(
            id = "skill-trust-authorization",
            scope = SettingScope.LOCAL,
            exactKey = "skill.ui.trust_overrides",
            reason = "trust is executable authorization and remains on the device that granted it",
        ),
        AuthorizationBoundary(
            id = "skill-enable-authorization",
            scope = SettingScope.LOCAL,
            exactKey = "skill.ui.enable_overrides",
            reason = "skill enablement is executable authorization and remains device-local",
        ),
        AuthorizationBoundary(
            id = "permission-preset-policy",
            scope = SettingScope.LOCAL,
            exactKey = "chat.defaultPermissionPreset",
            reason = "permission preset changes execution policy and remains device-local",
        ),
        AuthorizationBoundary(
            id = "plugin-theme-variant-selection",
            scope = SettingScope.LOCAL,
            exactKey = THEME_VARIANT_KEY,
            reason = "plugin theme variant selection is device-local",
        ),
        AuthorizationBoundary(
            id = "plugin-schedule-execution-history",
            scope = SettingScope.LOCAL,
            prefix = PLUGIN_SCHEDULE_PREFIX,
            reason = "plugin schedule state records device-local execution history",
        ),
        AuthorizationBoundary(
            id = "plugin-secrets",
            scope = SettingScope.SECRET,
            prefix = PLUGIN_SECRET_PREFIX,
            reason = "plugin secrets live in the local secure store and never sync as preferences",
        ),
    )

    private val localExact = AUTHORIZATION_BOUNDARIES
        .filter { it.scope == SettingScope.LOCAL && it.exactKey != null }
        .associateBy { it.exactKey!! }

    private val boundaryPrefixes = AUTHORIZATION_BOUNDARIES.filter { it.prefix != null }

    private val secretExact = setOf(
        "api_configs",
        "cloud_storage",
        "cloud_storage_credentials",
        "siliconflow.api_key",
        "siliconflow_api_key",
    )

    private val secretPrefixes = listOf(
        "secret",
        "internal.oauth.",
        "mcp.servers.",
        "mcp.tools.",
        "mcp.transport.",
        "plugin.ilinkbot.credentials",
        "web_search.api_key.",
        "web_search.searxng.api_key",
    )

    fun classify(key: String): SettingClass {
        if (key.isEmpty() || key.length > 512) {
            settingsError("INVALID_SETTING_ID", "setting id length")
        }
        localExact[key]?.let {
            return SettingClass(key, SettingScope.LOCAL, it.reason)
        }
        boundaryPrefixes.firstOrNull { key.startsWith(it.prefix!!) }?.let {
            return SettingClass(key, it.scope, it.reason)
        }
        if (isSecretKey(key)) {
            return SettingClass(key, SettingScope.SECRET, "credential or secure-store key")
        }
        if (key in PORTABLE_KEYS) {
            return SettingClass(key, SettingScope.PORTABLE, "vetted portable appearance/config key")
        }
        return SettingClass(key, SettingScope.LOCAL, "unknown or device-bound setting defaults to local")
    }

    fun isSecretKey(key: String): Boolean =
        key in secretExact ||
            key.endsWith(".api_key") ||
            key.endsWith(".apiKey") ||
            secretPrefixes.any { key.startsWith(it) }
}

internal fun normalizeCredentialKey(key: String): String {
    val output = StringBuilder(key.length)
    for (character in key) {
        when {
            character in 'A'..'Z' -> output.append(character.lowercaseChar())
            character in 'a'..'z' || character in '0'..'9' -> output.append(character)
            else -> Unit
        }
    }
    return output.toString()
}

fun settingsError(code: String, message: String): Nothing =
    throw DstuException(DstuError.validation(code, message))
