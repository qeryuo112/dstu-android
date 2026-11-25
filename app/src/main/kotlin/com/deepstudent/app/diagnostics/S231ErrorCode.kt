package com.deepstudent.app.diagnostics

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuErrorKind

/**
 * This is deliberately a small candidate catalogue, not a claim that every domain error is
 * covered. Never publish a source error's message, details, kind, or an unrecognised code.
 */
internal enum class S231ErrorCode(private val kind: DstuErrorKind, private val publicMessage: String) {
    TASK_FAILED(DstuErrorKind.INTERNAL, "task execution failed"),
    UNEXPECTED(DstuErrorKind.INTERNAL, "operation failed"),
    CANCELLED(DstuErrorKind.CANCELLED, "operation cancelled"),
    NETWORK(DstuErrorKind.UNAVAILABLE, "network operation failed"),
    STORAGE(DstuErrorKind.UNAVAILABLE, "storage operation failed"),
    AUTHENTICATION(DstuErrorKind.UNAUTHORIZED, "authentication failed"),
    INVALID_REPOSITORY(DstuErrorKind.VALIDATION, "repository validation failed"),
    ENCRYPTED_DATA_INVALID(DstuErrorKind.VALIDATION, "encrypted data validation failed"),
    CREDENTIAL_UNAVAILABLE(DstuErrorKind.UNAVAILABLE, "credential unavailable"),
    LIMIT_EXCEEDED(DstuErrorKind.VALIDATION, "operation limit exceeded"),
    INVALID_NOTE_TITLE(DstuErrorKind.VALIDATION, "note title is invalid"),
    INVALID_NOTE_TAGS(DstuErrorKind.VALIDATION, "note tags are invalid"),
    INVALID_NOTE_ID(DstuErrorKind.VALIDATION, "note identifier is invalid"),
    RESOURCE_INSUFFICIENT_SPACE(DstuErrorKind.UNAVAILABLE, "resource storage space is insufficient"),
    INVALID_ATTACHMENT(DstuErrorKind.VALIDATION, "attachment input is invalid"),
    INVALID_ATTACHMENT_SIZE(DstuErrorKind.VALIDATION, "attachment size is invalid"),
    ATTACHMENT_CORRUPT(DstuErrorKind.INTERNAL, "attachment verification failed"),
    INVALID_PLAN(DstuErrorKind.VALIDATION, "backup restore plan is invalid"),
    UNSAFE_ARCHIVE(DstuErrorKind.VALIDATION, "backup archive is unsafe"),
    HASH_MISMATCH(DstuErrorKind.VALIDATION, "backup content verification failed"),
    IO_FAILURE(DstuErrorKind.UNAVAILABLE, "backup I/O failed"),
    HEADER_INVALID(DstuErrorKind.VALIDATION, "encrypted container header is invalid"),
    AUTHENTICATION_FAILED(DstuErrorKind.UNAUTHORIZED, "encrypted container authentication failed"),
    MIGRATION_FAILED(DstuErrorKind.INTERNAL, "database migration failed"),
    DATABASE_OPERATION_FAILED(DstuErrorKind.INTERNAL, "database operation failed"),
    DATABASE_TIMED_OUT(DstuErrorKind.UNAVAILABLE, "database wait timed out"),
    S231_LIMIT_EXCEEDED(DstuErrorKind.VALIDATION, "diagnostic input exceeds the record limit"),
    OTHER(DstuErrorKind.INTERNAL, "unrecognised operation failure"),
    ;

    fun asDstuError(): DstuError = DstuError(name, kind, publicMessage)

    companion object {
        fun from(error: DstuError?): S231ErrorCode? {
            if (error == null) return null
            // An arbitrary error code is not itself a safe diagnostic field.
            return known(error.code) ?: OTHER
        }

        // Internal-only catalogue outcomes are never accepted from an external DstuError.
        private val externalCodes = listOf(
            UNEXPECTED, CANCELLED, NETWORK, STORAGE, AUTHENTICATION,
            INVALID_REPOSITORY, ENCRYPTED_DATA_INVALID, CREDENTIAL_UNAVAILABLE, LIMIT_EXCEEDED,
        ).associateBy { it.name }

        fun known(code: String?): S231ErrorCode? = externalCodes[code]

        /** New domain codes are only accepted from their registered producer/owner pair. */
        fun domain(event: S231Event?, owner: S231Owner?, code: String?): S231ErrorCode? {
            if (code == null) return null
            if (event == null || owner == null) return OTHER
            return domainCodes[event to owner]?.get(code) ?: OTHER
        }

        private val noteCodes = catalog(INVALID_NOTE_TITLE, INVALID_NOTE_TAGS, INVALID_NOTE_ID)
        private val resourceCodes = catalog(RESOURCE_INSUFFICIENT_SPACE, INVALID_ATTACHMENT, INVALID_ATTACHMENT_SIZE, ATTACHMENT_CORRUPT)
        private val backupCodes = catalog(INVALID_PLAN, UNSAFE_ARCHIVE, HASH_MISMATCH, IO_FAILURE)
        private val securityCodes = catalog(HEADER_INVALID, AUTHENTICATION_FAILED)
        private val migrationCodes = catalog(MIGRATION_FAILED)
        private val databaseCodes = catalog(DATABASE_OPERATION_FAILED, DATABASE_TIMED_OUT)
        private val runtimeCodes = catalog(TASK_FAILED, UNEXPECTED, CANCELLED)
        private val domainCodes = mapOf(
            (S231Event.NOTES_OPERATION to S231Owner.NOTES) to noteCodes,
            (S231Event.RESOURCES_OPERATION to S231Owner.RESOURCES) to resourceCodes,
            (S231Event.BACKUP_RESTORE to S231Owner.BACKUP) to backupCodes,
            (S231Event.SECURITY_CRYPTO to S231Owner.SECURITY) to securityCodes,
            (S231Event.DATABASE_MIGRATION to S231Owner.DATABASE) to migrationCodes,
            (S231Event.DATABASE_OPERATION to S231Owner.DATABASE) to databaseCodes,
            (S231Event.RUNTIME_FINISHED to S231Owner.RUNTIME) to runtimeCodes,
        )

        private fun catalog(vararg values: S231ErrorCode) = values.associateBy { it.name }
    }
}
