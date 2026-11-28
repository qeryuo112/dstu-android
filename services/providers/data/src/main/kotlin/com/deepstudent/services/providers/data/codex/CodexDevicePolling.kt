package com.deepstudent.services.providers.data.codex

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.ProviderCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import java.util.concurrent.TimeUnit

/** One device-authorization response after alias/clamp parsing; values never reach diagnostics. */
internal data class CodexDeviceCode(val deviceAuthId: String, val userCode: String, val intervalSeconds: Int, val expiresInSeconds: Long)

/**
 * Device-code login collaborator: user-code request, interval-gated polling and the
 * pending/slow_down retry budget. The service owns attempt installation and the typed
 * start result; cancellation stays wired to the live attempt's deferred, and a timeout
 * is not a reauthentication.
 */
internal class CodexDevicePolling(private val core: CodexAuthCore, private val endpoints: CodexOAuthEndpoints) {
    suspend fun requestDeviceCode(attempt: CodexAuthAttempt): CodexDeviceCode {
        val request = CodexAuthProtocol.deviceCodeRequest(endpoints, ProviderCancellation { attempt.cancel.isCompleted })
        val response = core.execute(request, ProviderCancellation { attempt.cancel.isCompleted }, CodexAuthStage.DEVICE_AUTHORIZATION)
        if (response.status !in 200..299) {
            throw CodexAuthException(
                CodexTokenPolicy.oauthRejection(CodexAuthStage.DEVICE_AUTHORIZATION, response.status, response.content),
            )
        }
        val root = response.content as? JsonValue.Object
            ?: throw CodexAuthException(coreFailure(CodexAuthFailureCode.MALFORMED_RESPONSE, CodexAuthStage.DEVICE_AUTHORIZATION))
        return CodexDeviceCode(
            deviceAuthId = requiredAlias(root, DEVICE_AUTH_ID_KEYS, CodexAuthStage.DEVICE_AUTHORIZATION),
            userCode = requiredAlias(root, USER_CODE_KEYS, CodexAuthStage.DEVICE_AUTHORIZATION),
            intervalSeconds = unsigned(root["interval"], INTERVAL_MIN_SECONDS, INTERVAL_MAX_SECONDS, DEFAULT_INTERVAL_SECONDS).toInt(),
            expiresInSeconds = unsigned(root["expires_in"], EXPIRY_MIN_SECONDS, EXPIRY_MAX_SECONDS, DEFAULT_EXPIRY_SECONDS),
        )
    }

    /** Polls until the account is installed, the attempt is cancelled or a terminal failure stops it. */
    suspend fun poll(attempt: CodexAuthAttempt) {
        val device = attempt.device ?: return
        val progress = DevicePollProgress()
        try {
            while (true) {
                if (!pollOnce(attempt, device, progress)) return
            }
        } catch (failure: CodexAuthException) {
            finishError(attempt.id, failure.failure, traced = true)
        } catch (_: CancellationException) {
            finishError(attempt.id, cancelledFailure(), traced = false)
        } catch (_: Exception) {
            finishError(attempt.id, coreFailure(CodexAuthFailureCode.INTERNAL, CodexAuthStage.DEVICE_POLL), traced = false)
        }
    }

    /** One wait+poll cycle: true keeps polling, false completes the login, throws end the attempt. */
    private suspend fun pollOnce(attempt: CodexAuthAttempt, device: CodexDeviceAttempt, progress: DevicePollProgress): Boolean {
        if (core.clock() >= attempt.expiresAtMillis) throw CodexAuthException(timeoutFailure())
        waitOrCancel(attempt.cancel, TimeUnit.SECONDS.toMillis(device.intervalSeconds + 3L))
        val response = requestPoll(attempt, device, progress) ?: return true
        if (response.status in 200..299) {
            completeLogin(attempt, response.content)
            return false
        }
        if (!isPending(response)) {
            throw CodexAuthException(CodexTokenPolicy.oauthRejection(CodexAuthStage.DEVICE_POLL, response.status, response.content))
        }
        progress.failures = 0
        return true
    }

    /** Returns null for a transient failure inside the retry budget; the caller keeps polling. */
    private suspend fun requestPoll(
        attempt: CodexAuthAttempt,
        device: CodexDeviceAttempt,
        progress: DevicePollProgress,
    ): CodexHttpResult.Response? = try {
        val request = CodexAuthProtocol.deviceTokenRequest(
            endpoints,
            device.deviceAuthId,
            attempt.prompt.userCode.orEmpty(),
            ProviderCancellation { attempt.cancel.isCompleted },
        )
        core.execute(request, ProviderCancellation { attempt.cancel.isCompleted }, CodexAuthStage.DEVICE_POLL)
    } catch (failure: CodexAuthException) {
        if (failure.failure.retryable && ++progress.failures < MAX_TRANSIENT_FAILURES) null else throw failure
    }

    /** Desktop keeps polling 403/404 and the authorization_pending / slow_down codes. */
    private fun isPending(response: CodexHttpResult.Response): Boolean {
        val code = CodexTokenPolicy.oauthErrorCode(response.content, response.status)
        return response.status == 403 || response.status == 404 || code == "authorization_pending" || code == "slow_down"
    }

    private suspend fun completeLogin(attempt: CodexAuthAttempt, body: JsonValue?) {
        core.persistAndInstall(attempt.id, deviceTokens(body))
        core.trace.record("device_poll", "COMPLETED", generation = core.state.read().generation)
    }

    private suspend fun deviceTokens(body: JsonValue?): CodexSession {
        val root = body as? JsonValue.Object
            ?: throw CodexAuthException(coreFailure(CodexAuthFailureCode.MALFORMED_RESPONSE, CodexAuthStage.DEVICE_POLL))
        val tokens = core.tokenRequest(
            CodexAuthStage.TOKEN_EXCHANGE,
            linkedMapOf(
                "grant_type" to "authorization_code",
                "code" to requiredAlias(root, AUTHORIZATION_CODE_KEYS, CodexAuthStage.DEVICE_POLL),
                "redirect_uri" to endpoints.deviceRedirectUrl,
                "client_id" to endpoints.clientId,
                "code_verifier" to requiredAlias(root, CODE_VERIFIER_KEYS, CodexAuthStage.DEVICE_POLL),
            ),
            ProviderCancellation { false },
        )
        return CodexTokenPolicy.sessionFromInitialTokens(tokens, core.clock())
    }

    private suspend fun waitOrCancel(cancel: CompletableDeferred<Unit>, millis: Long) {
        coroutineScope {
            val waiting = async {
                select<Unit> {
                    cancel.onAwait {}
                    onTimeout(millis) {}
                }
            }
            try {
                waiting.await()
            } finally {
                waiting.cancel()
            }
        }
        if (cancel.isCompleted) throw CancellationException("Codex device login cancelled")
    }

    private fun finishError(attemptId: String, failure: CodexAuthFailure, traced: Boolean) {
        core.state.finishAttemptError(attemptId, failure)
        if (traced) core.trace.record("device_poll", failure.code.name)
    }

    private fun timeoutFailure(): CodexAuthFailure = coreFailure(CodexAuthFailureCode.TIMEOUT, CodexAuthStage.DEVICE_POLL)

    private fun requiredAlias(root: JsonValue.Object, names: List<String>, stage: CodexAuthStage): String {
        for (name in names) {
            val value = (root[name] as? JsonValue.Text)?.value?.trim()
            if (!value.isNullOrEmpty()) return value
        }
        throw CodexAuthException(coreFailure(CodexAuthFailureCode.MALFORMED_RESPONSE, stage))
    }

    private fun unsigned(value: JsonValue?, minimum: Long, maximum: Long, fallback: Long): Long = when (value) {
        is JsonValue.Number -> value.literal.takeIf { it.matches(DIGITS) }?.toLongOrNull()?.coerceIn(minimum, maximum)
            ?: fallback

        is JsonValue.Text -> value.value.takeIf { it.matches(SIGNED_DIGITS) }?.let(::unsignedText)?.coerceIn(minimum, maximum)
            ?: fallback

        else -> fallback
    }

    private fun unsignedText(value: String): Long? =
        value.removePrefix("+").toULongOrNull()?.takeIf { number -> number <= Long.MAX_VALUE.toULong() }?.toLong()

    private class DevicePollProgress {
        var failures = 0
    }

    private companion object {
        const val MAX_TRANSIENT_FAILURES = 3
        const val DEFAULT_INTERVAL_SECONDS = 5L
        const val DEFAULT_EXPIRY_SECONDS = 900L
        const val INTERVAL_MIN_SECONDS = 1L
        const val INTERVAL_MAX_SECONDS = 60L
        const val EXPIRY_MIN_SECONDS = 60L
        const val EXPIRY_MAX_SECONDS = 900L
        val DEVICE_AUTH_ID_KEYS = listOf("device_auth_id")
        val USER_CODE_KEYS = listOf("user_code", "usercode")
        val AUTHORIZATION_CODE_KEYS = listOf("authorization_code")
        val CODE_VERIFIER_KEYS = listOf("code_verifier")
        val DIGITS = Regex("-?[0-9]+")
        val SIGNED_DIGITS = Regex("\\+?[0-9]+")
    }
}
