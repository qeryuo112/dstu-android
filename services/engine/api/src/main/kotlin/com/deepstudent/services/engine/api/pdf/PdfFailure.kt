package com.deepstudent.services.engine.api.pdf

/** Failures do not include document contents, file paths, or passwords. */
enum class PdfFailureCode {
    UNKNOWN,
    FILE,
    FORMAT,
    PASSWORD,
    SECURITY,
    PAGE,
    LIMIT,
    INVALID_ARGUMENT,
    CLOSED,
}

class PdfFailure(val code: PdfFailureCode) : RuntimeException("PDF: ${code.name}")
