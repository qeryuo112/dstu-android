package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.ResolvedProviderConfig
import java.util.Locale

/**
 * S3.08-local request/transport dialect for the custom/general/opencode_go/openrouter/
 * ollama relay members. The shared planner already mirrors the frozen generic adapter;
 * this boundary only applies the deltas the shared planner cannot know:
 *  - OpenRouter folds `reasoning_effort` into the `reasoning` object dialect and drops
 *    legacy `max_tokens`/`enabled` keys (history-compatible with configured reasoning objects);
 *  - relay hosts and the max-capable GPT family keep a requested `max` effort instead of
 *    the standard `xhigh` normalization.
 */
object RelayBodyDialect {
    // Frozen generic_openai.ts RELAY_HOSTS; siliconflow/openrouter are handled elsewhere.
    private val RELAY_HOSTS = setOf("custom", "general", "one-api", "one_api", "sub2api", "cpa", "together", "fireworks", "groq")

    /** Providers the S3.08 relay transport is installed for. */
    val MEMBERS: Set<String> = setOf("custom", "general", "opencode_go", "openrouter", "ollama")

    fun isOpenrouter(providerType: String, providerScope: String, baseUrl: String): Boolean {
        if (providerType.lowercase(Locale.ROOT) == "openrouter" || providerScope.lowercase(Locale.ROOT) == "openrouter") return true
        val host = runCatching { java.net.URI(baseUrl.trim()).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return false
        return host == "openrouter.ai" || host.endsWith(".openrouter.ai")
    }

    private fun isRelayHost(providerType: String, providerScope: String): Boolean {
        val host = (providerType.ifBlank { providerScope }).trim().lowercase(Locale.ROOT)
        return host in RELAY_HOSTS
    }

    /** Last path segment starts with gpt-5.6/gpt-6 and ends on a separator or nothing. */
    fun isMaxCapableGptFamily(model: String): Boolean {
        val segment = model.trim().lowercase(Locale.ROOT).split('/').last()
        val prefix = when {
            segment.startsWith("gpt-5.6") -> "gpt-5.6"
            segment.startsWith("gpt-6") -> "gpt-6"
            else -> return false
        }
        val rest = segment.substring(prefix.length)
        return rest.isEmpty() || rest[0] == '.' || rest[0] == '_' || rest[0] == '-'
    }

    private fun requestedEffort(resolved: ResolvedProviderConfig): String? =
        resolved.runtime.reasoning.reasoningEffort?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty)

    /**
     * Applies the dialect deltas to a serialized Chat Completions body. Bodies that do
     * not parse as objects (never produced by the shared planner) pass through unchanged.
     */
    fun apply(body: ByteArray?, resolved: ResolvedProviderConfig): ByteArray? {
        if (body == null) return null
        val root = runCatching { JsonValue.parse(body.decodeToString()) }.getOrNull() as? JsonValue.Object ?: return body
        val transformed = transform(root, resolved)
        return if (transformed === root) body else transformed.literal().toByteArray(Charsets.UTF_8)
    }

    /** Pure form used by tests: returns the same instance when nothing changed. */
    fun transform(body: JsonValue.Object, resolved: ResolvedProviderConfig): JsonValue.Object {
        val rawEffort = requestedEffort(resolved)
        val upgraded =
            rawEffort == "max" &&
                (isRelayHost(resolved.providerType, resolved.providerScope) || isMaxCapableGptFamily(resolved.profile.model))
        val openrouter = isOpenrouter(resolved.providerType, resolved.providerScope, resolved.baseUrl)
        return if (openrouter) foldOpenRouterEffort(body, upgraded) else upgradeRelayEffort(body, upgraded)
    }

    /** OpenRouter: move a planned `reasoning_effort` into the `reasoning` object dialect. */
    private fun foldOpenRouterEffort(body: JsonValue.Object, upgraded: Boolean): JsonValue.Object {
        val planned = body["reasoning_effort"] as? JsonValue.Text ?: return body
        val effort = if (upgraded && planned.value == "xhigh") "max" else planned.value
        val entries = LinkedHashMap(body.asMap()).apply { remove("reasoning_effort") }
        val reasoning = when (val existing = entries["reasoning"] as? JsonValue.Object) {
            null -> JsonValue.Object.of("effort" to JsonValue.text(effort))

            else -> JsonValue.Object.from(
                LinkedHashMap(existing.asMap()).apply {
                    remove("max_tokens")
                    remove("enabled")
                    put("effort", JsonValue.text(effort))
                },
            )
        }
        entries["reasoning"] = reasoning
        return JsonValue.Object.from(entries)
    }

    /** Relay hosts: a requested `max` effort survives instead of the standard `xhigh`. */
    private fun upgradeRelayEffort(body: JsonValue.Object, upgraded: Boolean): JsonValue.Object {
        val planned = body["reasoning_effort"] as? JsonValue.Text ?: return body
        if (!upgraded || planned.value != "xhigh") return body
        val entries = LinkedHashMap(body.asMap())
        entries["reasoning_effort"] = JsonValue.text("max")
        return JsonValue.Object.from(entries)
    }
}
