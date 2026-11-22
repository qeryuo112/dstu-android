package com.deepstudent.platform.security

data class SecurityDiagnostic(
    val code: String,
    val message: String,
)

object SecurityDiagnostics {
    const val REDACTED = "[redacted]"
    const val MAX_TEXT_CHARS = 16 * 1024

    private const val SECRET_NAME = "(?:[A-Za-z0-9_-]*[_-])?(?:password|passwd|passphrase|pwd|secret|token|credential|api[_-]?key|access[_-]?key|private[_-]?key|authorization|cookie|set-cookie|x-api-key|proxy-authorization)"
    private val jsonAssignment = Regex(
        """(?i)(["']$SECRET_NAME["']\s*:\s*)("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')""",
    )
    private val header = Regex(
        """(?im)((?:authorization|proxy-authorization|cookie|set-cookie|x-api-key)\s*[:=]\s*)[^\r\n]*""",
    )
    private val assignment = Regex(
        """(?i)($SECRET_NAME["']?\s*[=:]\s*)("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[^\s&,;\]}]+)""",
    )
    private val urlUserInfo = Regex("([A-Za-z][A-Za-z0-9+.-]*://)([^/@\\s]+)@")
    private val bearer = Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+")
    private val privateKey = Regex(
        "-----BEGIN [^-]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [^-]*PRIVATE KEY-----|$)",
    )

    fun forCredential(error: CredentialStoreError): SecurityDiagnostic =
        SecurityDiagnostic(error.name, error.fixedMessage)

    fun forDsbk(error: DsbkError): SecurityDiagnostic =
        SecurityDiagnostic(error.name, error.fixedMessage)

    /**
     * Best-effort only. Production security failures use the fixed typed
     * diagnostics above; arbitrary raw provider/user text is never safe to log.
     * The bound also prevents a diagnostic from consuming unbounded memory.
     */
    fun redact(text: String): String {
        if (text.length > MAX_TEXT_CHARS) return REDACTED
        var redacted = privateKey.replace(text, REDACTED)
        redacted = jsonAssignment.replace(redacted) { "${it.groupValues[1]}\"$REDACTED\"" }
        redacted = header.replace(redacted) { "${it.groupValues[1]}$REDACTED" }
        redacted = assignment.replace(redacted) { "${it.groupValues[1]}$REDACTED" }
        redacted = urlUserInfo.replace(redacted) { "${it.groupValues[1]}$REDACTED@" }
        return bearer.replace(redacted) { "${it.groupValues[1]}$REDACTED" }
    }
}
