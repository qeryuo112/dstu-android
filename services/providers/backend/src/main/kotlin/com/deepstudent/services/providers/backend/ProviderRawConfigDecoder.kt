package com.deepstudent.services.providers.backend

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.AuthMode
import com.deepstudent.services.providers.api.CredentialReference
import com.deepstudent.services.providers.api.ModelAdapter
import com.deepstudent.services.providers.api.ModelCapabilities
import com.deepstudent.services.providers.api.ModelFeatures
import com.deepstudent.services.providers.api.ModelParameters
import com.deepstudent.services.providers.api.ModelProfile
import com.deepstudent.services.providers.api.ModelRuntime
import com.deepstudent.services.providers.api.ProviderConfigDecoder
import com.deepstudent.services.providers.api.ProviderFailure
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderHeader
import com.deepstudent.services.providers.api.ProviderHeaders
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ProviderRawConfig
import com.deepstudent.services.providers.api.ProviderResult
import com.deepstudent.services.providers.api.ProviderSelection
import com.deepstudent.services.providers.api.RawCapabilities
import com.deepstudent.services.providers.api.RawFeatures
import com.deepstudent.services.providers.api.RawGenerationParameters
import com.deepstudent.services.providers.api.RawModelIdentity
import com.deepstudent.services.providers.api.RawModelLimits
import com.deepstudent.services.providers.api.RawModelProfile
import com.deepstudent.services.providers.api.RawModelState
import com.deepstudent.services.providers.api.RawReasoningFlags
import com.deepstudent.services.providers.api.RawSamplingParameters
import com.deepstudent.services.providers.api.RawSamplingValues
import com.deepstudent.services.providers.api.RawVendorConfig
import com.deepstudent.services.providers.api.RawVendorIdentity
import com.deepstudent.services.providers.api.ReasoningFlags
import com.deepstudent.services.providers.api.ReasoningParameters
import com.deepstudent.services.providers.api.ValidatedProviderInput
import com.deepstudent.services.providers.api.VendorConfig
import com.deepstudent.services.providers.api.VendorConnection
import com.deepstudent.services.providers.api.VendorIdentity
import com.deepstudent.services.providers.api.VendorProtocolDefaults
import com.deepstudent.services.providers.api.flatMap
import com.deepstudent.services.providers.api.map
import java.util.Locale

/**
 * Validates raw unknown data before constructing [ProviderSelection]. Every public
 * malformed case is a fixed [ProviderFailure], not an exception from a data class.
 */
object ProviderRawConfigDecoder : ProviderConfigDecoder {
    override fun decode(raw: ProviderRawConfig): ProviderResult<ValidatedProviderInput> = decodeSelection(raw).map { selection ->
        ValidatedProviderInput(selection.vendor, selection.profile, selection.runtime)
    }

    override fun decodeSelection(raw: ProviderRawConfig): ProviderResult<ProviderSelection> =
        RawProviderJson.decode(raw).flatMap { (vendor, profile) ->
            decodeSelection(vendor, profile)
        }

    fun decodeVendor(raw: RawVendorConfig): ProviderResult<RawVendorConfig> {
        validateVendorIdentity(raw.identity)?.let { return it }
        val provider = raw.identity.providerType.trim().lowercase(Locale.ROOT)
        if (ProviderRegistry.find(provider) == null) return fail(ProviderFailureCode.UNKNOWN_PROVIDER)
        validateVendorEnums(raw)?.let { return it }
        validateCredential(raw.credentialKey)?.let { return it }
        val headers = validateHeadersJson(raw.headersJson) ?: return fail(ProviderFailureCode.INVALID_HEADERS)
        val endpoint = validateEndpoint(raw.baseUrl) ?: return fail(ProviderFailureCode.INVALID_ENDPOINT)
        return ProviderResult.Success(
            raw.copy(
                identity = raw.identity.copy(providerType = provider),
                authMode = raw.authMode?.trim()?.lowercase(Locale.ROOT),
                protocol = raw.protocol?.trim()?.lowercase(Locale.ROOT),
                headersJson = headers,
                credentialKey = raw.credentialKey?.takeIf { it.isNotBlank() },
                baseUrl = endpoint,
            ),
        )
    }

    fun decodeProfile(raw: RawModelProfile): ProviderResult<RawModelProfile> {
        validateProfileIdentity(raw.identity)?.let { return it }
        raw.protocol?.let { if (ProviderProtocol.fromWire(it) == null) return fail(ProviderFailureCode.UNSUPPORTED_PROTOCOL) }
        if (ModelAdapter.fromWire(raw.adapter) == null) return fail(ProviderFailureCode.INVALID_MODEL_PARAMETERS)
        validateGeneration(raw.generation)?.let { return it }
        validateSampling(raw.sampling)?.let { return it }
        if (!validStatus(raw.sampling.state.status)) return fail(ProviderFailureCode.INVALID_CONFIGURATION)
        val extraBody = validateExtraBody(raw.generation.extraBodyJson) ?: return fail(ProviderFailureCode.INVALID_MODEL_PARAMETERS)
        return ProviderResult.Success(
            raw.copy(
                protocol = raw.protocol?.trim()?.lowercase(Locale.ROOT),
                adapter = raw.adapter.trim().lowercase(Locale.ROOT),
                generation = raw.generation.copy(extraBodyJson = extraBody),
            ),
        )
    }

    /** Builds selection only after both raw parts passed the typed preflight. */
    fun decodeSelection(rawVendor: RawVendorConfig, rawProfile: RawModelProfile): ProviderResult<ProviderSelection> =
        decodeVendor(rawVendor).flatMap { vendor ->
            decodeProfile(rawProfile).flatMap { profile -> buildSelection(vendor, profile) }
        }

    private fun buildSelection(vendor: RawVendorConfig, profile: RawModelProfile): ProviderResult<ProviderSelection> {
        if (vendor.identity.id != profile.identity.vendorId) return fail(ProviderFailureCode.INVALID_CONFIGURATION)
        val protocol = profile.protocol?.let(ProviderProtocol::fromWire)
        val authMode = vendor.authMode?.let(AuthMode::fromWire)
        val credentialKey = vendor.credentialKey
        val credential: ProviderResult<CredentialReference?> = if (credentialKey == null) {
            ProviderResult.Success(null)
        } else {
            safeCredential(credentialKey)
        }
        return credential.flatMap { reference ->
            ProviderResult.Success(
                ProviderSelection(
                    vendor = VendorConfig(
                        identity = VendorIdentity(vendor.identity.id, vendor.identity.providerType, vendor.identity.name),
                        connection = VendorConnection(vendor.baseUrl, authMode, reference),
                        protocolDefaults = VendorProtocolDefaults(protocol, vendor.supportsOpenaiResponses),
                        headers = parseHeaders(HeaderDecoder.decode(vendor.headersJson)),
                    ),
                    profile = ModelProfile(
                        id = profile.identity.id,
                        vendorId = profile.identity.vendorId,
                        model = profile.identity.model,
                        label = profile.identity.label,
                        providerScope = profile.identity.providerScope,
                        protocol = protocol,
                        adapter = checkNotNull(ModelAdapter.fromWire(profile.adapter)),
                    ),
                    runtime = buildRuntime(profile),
                ),
            )
        }
    }

    private fun buildRuntime(profile: RawModelProfile): ModelRuntime {
        val generation = profile.generation
        val sampling = profile.sampling
        return ModelRuntime(
            capabilities = ModelCapabilities(
                sampling.state.capabilities.text,
                sampling.state.capabilities.streaming,
                sampling.state.capabilities.tools,
                sampling.state.capabilities.images,
                sampling.state.capabilities.audio,
                sampling.state.capabilities.video,
            ),
            features = ModelFeatures(
                sampling.state.features.reasoning,
                sampling.state.features.embeddings,
                sampling.state.features.imageGeneration,
                sampling.state.features.reranking,
                sampling.state.features.favorite,
            ),
            reasoning = ReasoningParameters(
                reasoningEffort = generation.reasoningEffort,
                thinkingBudget = generation.thinkingBudget?.toInt(),
                includeThoughts = generation.includeThoughts,
                reasoningMode = generation.reasoningMode,
                repetitionPenalty = sampling.values.repetitionPenalty?.toDouble(),
                presencePenalty = sampling.values.presencePenalty?.toDouble(),
                reasoningSplit = sampling.values.reasoningSplit,
                flags = ReasoningFlags(
                    isReasoning = generation.reasoningFlags.isReasoning,
                    thinkingEnabled = generation.reasoningFlags.thinkingEnabled,
                ),
            ),
            parameters = ModelParameters(
                maxOutputTokens = generation.maxOutputTokens.toInt(),
                temperature = checkNotNull(generation.temperature.toDoubleOrNull()),
                topP = sampling.values.topP?.toDouble(),
                topK = sampling.values.topK?.toInt(),
                minP = sampling.values.minP?.toDouble(),
                frequencyPenalty = sampling.values.frequencyPenalty?.toDouble(),
                typedExtraBody = decodeTypedExtraBody(generation.extraBodyJson),
                extraBody = decodeExtraBody(generation.extraBodyJson),
            ),
            maxTokensLimit = sampling.limits.maxTokensLimit?.toInt(),
            contextWindow = sampling.limits.contextWindow?.toInt(),
            enabled = sampling.state.enabled,
            status = sampling.state.status,
        )
    }

    private fun validateVendorEnums(raw: RawVendorConfig): ProviderResult.Failure? = when {
        raw.authMode?.let(AuthMode::fromWire) == null && raw.authMode != null -> fail(ProviderFailureCode.INVALID_AUTH_MODE)
        raw.protocol?.let(ProviderProtocol::fromWire) == null && raw.protocol != null -> fail(ProviderFailureCode.UNSUPPORTED_PROTOCOL)
        else -> null
    }

    private fun validStatus(status: String): Boolean = status.isNotBlank() && status.length <= 64

    private fun validateVendorIdentity(identity: RawVendorIdentity): ProviderResult.Failure? = when {
        identity.id.isBlank() || identity.id.length > 128 -> fail(ProviderFailureCode.INVALID_CONFIGURATION)
        identity.name.isBlank() || identity.name.length > 256 -> fail(ProviderFailureCode.INVALID_CONFIGURATION)
        else -> null
    }

    private fun validateProfileIdentity(identity: RawModelIdentity): ProviderResult.Failure? = when {
        !MODEL_ID.matches(identity.id) || !MODEL_ID.matches(identity.vendorId) -> fail(ProviderFailureCode.INVALID_CONFIGURATION)
        identity.model.isBlank() || identity.model.length > 512 || identity.label.isBlank() -> fail(ProviderFailureCode.INVALID_MODEL)
        identity.providerScope?.length?.let { it > 64 } == true -> fail(ProviderFailureCode.INVALID_CONFIGURATION)
        else -> null
    }

    private fun validateCredential(value: String?): ProviderResult.Failure? =
        if (value != null && !CREDENTIAL.matches(value)) fail(ProviderFailureCode.MISSING_CREDENTIAL_REFERENCE) else null

    private fun validateGeneration(value: RawGenerationParameters): ProviderResult.Failure? {
        if (value.maxOutputTokens !in 1..1_048_576) return fail(ProviderFailureCode.INVALID_MODEL_PARAMETERS)
        val temperature = value.temperature.toDoubleOrNull()
        if (temperature == null || !temperature.isFinite() || temperature !in 0.0..2.0) {
            return fail(ProviderFailureCode.INVALID_MODEL_PARAMETERS)
        }
        val thinkingBudget = value.thinkingBudget
        if (thinkingBudget != null && thinkingBudget.toIntOrNull() !in -1..Int.MAX_VALUE) {
            return fail(ProviderFailureCode.INVALID_MODEL_PARAMETERS)
        }
        return null
    }

    private fun validateSampling(value: RawSamplingParameters): ProviderResult.Failure? {
        validateRanges(value.values)?.let { return it }
        val maxTokensLimit = value.limits.maxTokensLimit
        if (maxTokensLimit != null && maxTokensLimit.toIntOrNull() !in 1..1_048_576) {
            return fail(ProviderFailureCode.INVALID_MODEL_PARAMETERS)
        }
        val contextWindow = value.limits.contextWindow
        if (contextWindow != null && contextWindow.toIntOrNull() !in 1..10_485_760) {
            return fail(ProviderFailureCode.INVALID_MODEL_PARAMETERS)
        }
        return null
    }

    private fun validateRanges(value: RawSamplingValues): ProviderResult.Failure? = validateNumericRange(value.topP, 0.0..1.0)
        ?: validateIntegerRange(value.topK, 0..1_000_000)
        ?: validateNumericRange(value.minP, 0.0..1.0)
        ?: validateFinite(value.frequencyPenalty)
        ?: validateFinite(value.repetitionPenalty)
        ?: validateFinite(value.presencePenalty)

    private fun validateNumericRange(value: String?, range: ClosedFloatingPointRange<Double>): ProviderResult.Failure? =
        if (value != null && !isDoubleIn(value, range)) fail(ProviderFailureCode.INVALID_MODEL_PARAMETERS) else null

    private fun validateIntegerRange(value: String?, range: IntRange): ProviderResult.Failure? =
        if (value != null && value.toIntOrNull() !in range) fail(ProviderFailureCode.INVALID_MODEL_PARAMETERS) else null

    private fun validateFinite(value: String?): ProviderResult.Failure? =
        if (value != null && !isFinite(value)) fail(ProviderFailureCode.INVALID_MODEL_PARAMETERS) else null

    private fun validateHeadersJson(value: String): String? = try {
        HeaderDecoder.decode(value)
    } catch (_: Exception) {
        null
    }
    private fun validateExtraBody(value: String): String? = try {
        ExtraBodyDecoder.decode(value)
    } catch (_: Exception) {
        null
    }

    private fun decodeExtraBody(value: String): Map<String, String> = JsonValue.parseObject(value).asMap().mapValues { (_, item) ->
        when (item) {
            is JsonValue.Text -> item.value
            else -> item.literal()
        }
    }

    private fun decodeTypedExtraBody(value: String): Map<String, String> =
        JsonValue.parseObject(value).asMap().mapValues { (_, item) -> item.literal() }

    private fun validateEndpoint(value: String): String? = try {
        EndpointValidator.validate(value)
    } catch (_: Exception) {
        null
    }

    private fun isDoubleIn(value: String, range: ClosedFloatingPointRange<Double>): Boolean =
        value.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { it in range } == true

    private fun isFinite(value: String): Boolean = value.toDoubleOrNull()?.isFinite() == true

    private fun safeCredential(value: String): ProviderResult<CredentialReference> = try {
        ProviderResult.Success(CredentialReference(value))
    } catch (
        _: Exception,
    ) {
        fail(ProviderFailureCode.MISSING_CREDENTIAL_REFERENCE)
    }

    private fun parseHeaders(value: String): List<ProviderHeader> {
        val result = JsonValue.parseObject(value).asMap().map { (name, json) ->
            val text = (json as? JsonValue.Text)?.value ?: throw IllegalArgumentException("header value")
            ProviderHeader(name, text)
        }
        ProviderHeaders.validate(result)
        return result
    }

    private fun fail(code: ProviderFailureCode): ProviderResult.Failure = ProviderResult.Failure(ProviderFailure(code))

    private val CREDENTIAL = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    private val MODEL_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
}

private object HeaderDecoder {
    fun decode(json: String): String {
        if (json.isBlank()) throw IllegalArgumentException("headers required")
        val root = JsonValue.parseObject(json)
        if (root.isEmpty()) return json
        root.asMap().forEach { (name, value) ->
            if (name.isBlank() || value !is JsonValue.Text) throw IllegalArgumentException("header")
        }
        return json
    }
}

private object ExtraBodyDecoder {
    private val KEY = Regex("[A-Za-z0-9_.-]{1,64}")

    fun decode(json: String): String {
        if (json.isBlank()) throw IllegalArgumentException("extra body")
        val root = JsonValue.parseObject(json)
        root.asMap().forEach { (name, value) ->
            if (!KEY.matches(name) || value.literal().length > 4096) throw IllegalArgumentException("extra body")
        }
        return json
    }
}

private object EndpointValidator {
    fun validate(value: String): String {
        val uri = java.net.URI(value.trim())
        if (uri.scheme != "https" || uri.host == null || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null || uri.path.isNullOrEmpty()
        ) {
            throw IllegalArgumentException("invalid endpoint")
        }
        return value.trim()
    }
}

private object RawProviderJson {
    fun decode(raw: ProviderRawConfig): ProviderResult<Pair<RawVendorConfig, RawModelProfile>> = try {
        val root = JsonValue.parseObject(raw.json)
        val vendor = parseVendor(root)
        val unknownProvider = ProviderRegistry.find(vendor.identity.providerType) == null
        if (unknownProvider) {
            ProviderResult.Failure(ProviderFailure(ProviderFailureCode.UNKNOWN_PROVIDER))
        } else {
            val profile = parseProfile(root)
            ProviderResult.Success(vendor to profile)
        }
    } catch (_: Exception) {
        ProviderResult.Failure(ProviderFailure(ProviderFailureCode.INVALID_CONFIGURATION))
    }

    private fun parseVendor(root: JsonValue.Object): RawVendorConfig {
        val vendor = field(root, "vendor")
        return RawVendorConfig(
            identity = RawVendorIdentity(
                id = text(vendor, "id"),
                providerType = text(vendor, "providerType"),
                name = text(vendor, "name"),
            ),
            baseUrl = text(vendor, "baseUrl"),
            authMode = nullableText(vendor, "authMode"),
            protocol = nullableText(vendor, "protocol"),
            supportsOpenaiResponses = boolOrNull(vendor, "supportsOpenaiResponses"),
            credentialKey = nullableText(vendor, "credentialKey"),
            headersJson = (vendor["headers"] as? JsonValue.Object)?.literal()
                ?: throw IllegalArgumentException("headers required"),
        )
    }

    private fun parseProfile(root: JsonValue.Object): RawModelProfile {
        val profile = field(root, "profile")
        val parameters = field(profile, "parameters")
        val reasoning = field(profile, "reasoning")
        return RawModelProfile(
            identity = RawModelIdentity(
                id = text(profile, "id"),
                vendorId = text(profile, "vendorId"),
                model = text(profile, "model"),
                label = text(profile, "label"),
                providerScope = nullableText(profile, "providerScope"),
            ),
            protocol = nullableText(profile, "protocol"),
            adapter = text(profile, "adapter"),
            generation = generation(parameters, reasoning),
            sampling = sampling(profile, parameters, reasoning),
        )
    }

    private fun generation(parameters: JsonValue.Object, reasoning: JsonValue.Object) = RawGenerationParameters(
        maxOutputTokens = long(parameters, "maxOutputTokens"),
        temperature = numericText(parameters, "temperature") ?: throw IllegalArgumentException("temperature required"),
        reasoningEffort = nullableText(reasoning, "reasoningEffort"),
        thinkingBudget = numericText(reasoning, "thinkingBudget"),
        includeThoughts = boolOrNull(reasoning, "includeThoughts") ?: false,
        reasoningMode = nullableText(reasoning, "reasoningMode"),
        reasoningFlags = RawReasoningFlags(boolOrNull(reasoning, "isReasoning"), boolOrNull(reasoning, "thinkingEnabled")),
        extraBodyJson = field(parameters, "extraBody").literal(),
    )

    private fun sampling(profile: JsonValue.Object, parameters: JsonValue.Object, reasoning: JsonValue.Object) = RawSamplingParameters(
        values = RawSamplingValues(
            topP = numericText(parameters, "topP"),
            topK = numericText(parameters, "topK"),
            minP = numericText(parameters, "minP"),
            frequencyPenalty = numericText(parameters, "frequencyPenalty"),
            repetitionPenalty = numericText(reasoning, "repetitionPenalty"),
            presencePenalty = numericText(reasoning, "presencePenalty"),
            reasoningSplit = boolOrNull(reasoning, "reasoningSplit"),
        ),
        limits = RawModelLimits(numericText(profile, "maxTokensLimit"), numericText(profile, "contextWindow")),
        state = state(profile),
    )

    private fun state(profile: JsonValue.Object): RawModelState {
        val capabilities = field(profile, "capabilities")
        val features = field(profile, "features")
        return RawModelState(
            enabled = bool(profile, "enabled"),
            status = text(profile, "status"),
            isBuiltin = bool(profile, "isBuiltin"),
            capabilities = RawCapabilities(
                text = bool(capabilities, "text"),
                streaming = bool(capabilities, "streaming"),
                tools = bool(capabilities, "tools"),
                images = bool(capabilities, "images"),
                audio = bool(capabilities, "audio"),
                video = bool(capabilities, "video"),
            ),
            features = RawFeatures(
                reasoning = bool(features, "reasoning"),
                embeddings = bool(features, "embeddings"),
                imageGeneration = bool(features, "imageGeneration"),
                reranking = bool(features, "reranking"),
                favorite = bool(features, "favorite"),
            ),
        )
    }

    private fun numericText(value: JsonValue, name: String): String? = when (val field = member(value, name)) {
        null, JsonValue.Null -> null
        is JsonValue.Text -> field.value
        is JsonValue.Number -> field.literal
        else -> throw IllegalArgumentException("number required")
    }

    private fun field(value: JsonValue, name: String): JsonValue.Object =
        member(value, name) as? JsonValue.Object ?: throw IllegalArgumentException("object required")

    private fun text(value: JsonValue, name: String): String =
        (member(value, name) as? JsonValue.Text)?.value ?: throw IllegalArgumentException("text required")

    private fun nullableText(value: JsonValue, name: String): String? = when (val field = member(value, name)) {
        null, JsonValue.Null -> null
        is JsonValue.Text -> field.value
        else -> throw IllegalArgumentException("text or null required")
    }

    private fun bool(value: JsonValue, name: String): Boolean =
        (member(value, name) as? JsonValue.Bool)?.value ?: throw IllegalArgumentException("boolean required")

    private fun boolOrNull(value: JsonValue, name: String): Boolean? = when (val field = member(value, name)) {
        null, JsonValue.Null -> null
        is JsonValue.Bool -> field.value
        else -> throw IllegalArgumentException("boolean or null required")
    }

    private fun long(value: JsonValue, name: String): Long = (member(value, name) as? JsonValue.Number)?.asLongOrNull()
        ?: throw IllegalArgumentException("integer required")

    private fun member(value: JsonValue, name: String): JsonValue? = (value as JsonValue.Object)[name]
}
