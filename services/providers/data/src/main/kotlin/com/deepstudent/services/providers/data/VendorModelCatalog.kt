package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.platform.network.HttpLimits
import com.deepstudent.platform.network.NetworkError
import com.deepstudent.platform.network.NetworkException
import com.deepstudent.platform.network.NetworkHeader
import com.deepstudent.platform.network.NetworkRequest
import com.deepstudent.platform.network.NetworkTransport
import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.api.ProviderHeader
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * Independently callable vendor model catalog port. The OpenCode Go built-in catalog
 * is returned with zero network calls; every other vendor is fetched through the
 * injected transport with the frozen pagination, timeout clamp and sanitized errors.
 */
interface VendorModelListPort {
    suspend fun fetch(request: VendorModelsRequest, credentialText: String?, cancellation: ProviderCancellation): List<FetchedVendorModel>
}

/** Vendor identity, endpoint and explicit protocol of one catalog request. */
data class VendorModelsConnection(val vendorId: String, val providerType: String, val baseUrl: String, val apiProtocol: String? = null)

/** Call-scoped authentication and timeout for the catalog; never put these inputs in diagnostics. */
class VendorModelsAccess(
    val authModeWire: String?,
    val headers: List<ProviderHeader> = emptyList(),
    val backupApiKeys: List<String> = emptyList(),
    val defaultTimeoutMillis: Long? = null,
    val allowLoopback: Boolean = false,
) {
    override fun toString(): String = "VendorModelsAccess(redacted)"
}

/** One catalog request; connection and access stay grouped to keep the contract reviewable. */
data class VendorModelsRequest(val connection: VendorModelsConnection, val access: VendorModelsAccess)

internal class DefaultVendorModelListPort(private val network: NetworkTransport, private val logger: Logger = NoopLogger) :
    VendorModelListPort {
    override suspend fun fetch(
        request: VendorModelsRequest,
        credentialText: String?,
        cancellation: ProviderCancellation,
    ): List<FetchedVendorModel> {
        if (request.connection.vendorId == OpenCodeGoCatalog.VENDOR_ID ||
            request.connection.providerType.trim().lowercase(Locale.ROOT) == OpenCodeGoCatalog.PROVIDER_TYPE
        ) {
            return builtinCatalog()
        }
        val trace = S308Trace(logger, request.connection.providerType, "providers.s308.models")
        trace.record(S308Trace.STAGE_STARTED)
        return try {
            checkCancellation(cancellation)
            val key = resolveCredentialText(request.access, credentialText)
            val (url, kind) = ModelListEndpoint.derive(
                request.connection.baseUrl,
                request.connection.providerType,
                request.connection.apiProtocol,
            )
            ModelListEndpoint.validate(url, request.access.allowLoopback)
            val headers = VendorModelHeaders.build(request.access.headers, key, kind)
            val models = whileS308ProviderActive(cancellation) {
                fetchPages(url, request.access, headers.map { NetworkHeader(it.name, it.value) }, kind, cancellation)
            }
            checkCancellation(cancellation)
            trace.record(S308Trace.STAGE_COMPLETED, fields = JsonValue.Object.Builder().put("models", models.size).build())
            models
        } catch (cancelled: CancellationException) {
            trace.record(S308Trace.STAGE_CANCELLED)
            currentCoroutineContext().ensureActive()
            checkCancellation(cancellation)
            throw cancelled
        } catch (failure: ModelListException) {
            trace.record(listFailureStage(failure.code), failure.code.name)
            throw failure
        }
    }

    private suspend fun fetchPages(
        modelsUrl: String,
        access: VendorModelsAccess,
        headers: List<NetworkHeader>,
        kind: ModelListEndpoint.Kind,
        cancellation: ProviderCancellation,
    ): List<FetchedVendorModel> {
        val models = LinkedHashMap<String, FetchedVendorModel>()
        val timeoutMillis = access.defaultTimeoutMillis?.coerceIn(MIN_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS) ?: DEFAULT_TIMEOUT_MILLIS
        var cursor: String? = null
        var pages = 0
        while (pages < MAX_PAGES) {
            checkCancellation(cancellation)
            val pageUrl = VendorModelPage.withQuery(modelsUrl, kind, cursor)
            val body = fetchPage(pageUrl, headers, timeoutMillis)
            val parsed = parseBody(body, kind)
            models.putAll(parsed.models)
            cursor = parsed.cursor
            if (cursor.isNullOrEmpty()) break
            pages += 1
        }
        return models.values.sortedWith(compareBy { it.id })
    }

    private suspend fun fetchPage(url: String, headers: List<NetworkHeader>, timeoutMillis: Long): String {
        val response = try {
            withTimeoutOrNull(timeoutMillis) {
                network.execute(NetworkRequest(url, "GET", headers), HttpLimits())
            }
        } catch (failure: NetworkException) {
            currentCoroutineContext().ensureActive()
            if (failure.error == NetworkError.PROXY_AUTHENTICATION) {
                throw ModelListException(ModelListFailureCode.HTTP_STATUS, 407)
            }
            throw ModelListException(ModelListFailureCode.FETCH_FAILED)
        } ?: throw ModelListException(ModelListFailureCode.TIMEOUT)
        if (response.head.statusCode !in 200..299) {
            throw ModelListException(ModelListFailureCode.HTTP_STATUS, response.head.statusCode)
        }
        return response.body.decodeToString()
    }

    private fun listFailureStage(code: ModelListFailureCode): String =
        if (code == ModelListFailureCode.CANCELLED) S308Trace.STAGE_CANCELLED else S308Trace.STAGE_FAILED

    private fun parseBody(body: String, kind: ModelListEndpoint.Kind): ParsedPage = try {
        VendorModelPage.parsePage(JsonValue.parse(body), kind)
    } catch (failure: ModelListException) {
        throw failure
    } catch (failure: Exception) {
        throw ModelListException(ModelListFailureCode.PARSE_FAILED)
    }

    private fun resolveCredentialText(access: VendorModelsAccess, credentialText: String?): String {
        val usesNoAuth = access.authModeWire?.trim()?.lowercase(Locale.ROOT) == "none"
        if (usesNoAuth) return ""
        if (credentialText.isNullOrBlank()) {
            return access.backupApiKeys.map { it.trim() }.firstOrNull { it.isNotEmpty() }
                ?: throw ModelListException(ModelListFailureCode.MISSING_API_KEY)
        }
        return credentialText.trim()
    }

    private fun checkCancellation(cancellation: ProviderCancellation) {
        if (cancellation.isCancelled()) throw ModelListException(ModelListFailureCode.CANCELLED)
    }

    private fun builtinCatalog(): List<FetchedVendorModel> = OPENCODE_GO_BUILTIN_MODELS.map { model ->
        FetchedVendorModel(model.model, model.label, null, model.maxOutputTokens)
    }

    private companion object {
        const val MAX_PAGES = 20
        const val MIN_TIMEOUT_MILLIS = 1_000L
        const val MAX_TIMEOUT_MILLIS = 120_000L
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
    }
}

/** Composition entry: builds the port over any transport. */
fun vendorModelListPort(network: NetworkTransport, logger: Logger = NoopLogger): VendorModelListPort =
    DefaultVendorModelListPort(network, logger)
