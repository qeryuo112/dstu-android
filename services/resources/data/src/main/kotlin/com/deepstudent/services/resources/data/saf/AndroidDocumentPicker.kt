package com.deepstudent.services.resources.data.saf

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.time.ClockChecks
import com.deepstudent.core.common.time.MonotonicClock
import com.deepstudent.services.resources.api.DocumentCancellation
import com.deepstudent.services.resources.api.DocumentCleanup
import com.deepstudent.services.resources.api.DocumentNewDestination
import com.deepstudent.services.resources.api.DocumentSource
import com.deepstudent.services.resources.api.DocumentWriteStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * System picker only. Caller must deliver the unmodified Activity result for the corresponding
 * request; a forged result does not prove ACTION_CREATE_DOCUMENT provenance. Never log URI values.
 */
class AndroidDocumentPicker(context: Context, private val logger: Logger, private val clock: MonotonicClock) {
    private val context = context.applicationContext

    fun openIntent(mimeType: String): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE).setType(mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)

    /** Keep this one-shot request with the calling operation until its matching result arrives. */
    fun createRequest(mimeType: String, displayName: String): AndroidCreateDocumentRequest =
        AndroidCreateDocumentRequest(
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mimeType)
                .putExtra(Intent.EXTRA_TITLE, displayName)
                .addFlags(READ_WRITE or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),
            ::acceptCreated,
        )

    /** Accept only the actual result of the matching ACTION_OPEN_DOCUMENT invocation. */
    fun acceptOpen(resultCode: Int, data: Intent?, persist: Boolean = true): AndroidDocumentSelection {
        val trace = PickerTrace(logger, clock)
        val selected = select(resultCode, data, Intent.FLAG_GRANT_READ_URI_PERMISSION, persist, trace = trace)
        return when (selected) {
            is Selected.Failed -> selected.result
            is Selected.Granted -> AndroidDocumentSelection.Opened(
                AndroidDocumentSource(context, selected.uri, trace.id), selected.persisted,
            )
        }
    }

    /**
     * Accept only a fresh result of ACTION_CREATE_DOCUMENT. This is a one-shot export target;
     * never turn an arbitrary existing URI or a restored handle into a writable destination.
     */
    private fun acceptCreated(resultCode: Int, data: Intent?, persist: Boolean): AndroidDocumentSelection {
        val trace = PickerTrace(logger, clock)
        val selected = select(resultCode, data, READ_WRITE, persist, requireDocument = true, trace = trace)
        return when (selected) {
            is Selected.Failed -> rejectedCreation(resultCode, data, selected.result, trace)
            is Selected.Granted -> AndroidDocumentSelection.Created(
                AndroidNewDocument(context, selected.uri, trace.id), selected.persisted,
            )
        }
    }

    private fun rejectedCreation(
        resultCode: Int, data: Intent?, selection: AndroidDocumentSelection, trace: PickerTrace,
    ): AndroidDocumentSelection {
        if (resultCode != Activity.RESULT_OK || selection !is AndroidDocumentSelection.Rejected) return selection
        val deleted = cleanupRejectedTarget(data)
        val cleanup = if (deleted) DocumentCleanup.DELETE_REPORTED else DocumentCleanup.MAY_REMAIN
        trace.emit("completed", cleanup.name, "CLEANUP")
        return selection.copy(cleanup = cleanup)
    }

    private fun cleanupRejectedTarget(data: Intent?): Boolean {
        val uri = data?.data ?: return false
        if (!validContentUri(uri) || !DocumentsContract.isDocumentUri(context, uri)) return false
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (data.flags and write == 0 || !hasGrant(context, uri, write)) return false
        return try { DocumentsContract.deleteDocument(context.contentResolver, uri) } catch (_: Exception) { false }
    }

    /** Restore a saved reference only while Android still reports its persistable read grant. */
    fun restoreRead(reference: String): AndroidDocumentSelection {
        val trace = PickerTrace(logger, clock)
        return try {
            val uri = Uri.parse(reference)
            val granted = validContentUri(uri) && context.contentResolver.persistedUriPermissions
                .any { it.uri == uri && it.isReadPermission }
            if (!granted || !hasGrant(context, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)) {
                trace.emit("failed", "ACCESS_DENIED")
                AndroidDocumentSelection.Rejected(DocumentSelectionFailure.ACCESS_DENIED)
            } else {
                trace.emit("completed")
                AndroidDocumentSelection.Opened(AndroidDocumentSource(context, uri, trace.id), true)
            }
        } catch (_: Exception) {
            trace.emit("failed", "ACCESS_DENIED")
            AndroidDocumentSelection.Rejected(DocumentSelectionFailure.ACCESS_DENIED)
        }
    }

    /** Release a saved read grant. A separate, still-live transient grant may remain until its owner ends. */
    fun releasePersistedRead(reference: String): Boolean {
        val trace = PickerTrace(logger, clock)
        return try {
            val uri = Uri.parse(reference)
            if (!validContentUri(uri)) {
                trace.emit("failed", "INVALID_URI")
                false
            } else {
                context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val released = context.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }
                trace.emit(if (released) "completed" else "failed", if (released) "RELEASED" else "ACCESS_DENIED")
                released
            }
        } catch (_: SecurityException) {
            trace.emit("failed", "ACCESS_DENIED")
            false
        }
    }

    private fun select(
        resultCode: Int,
        data: Intent?,
        required: Int,
        persist: Boolean,
        requireDocument: Boolean = false,
        trace: PickerTrace,
    ): Selected {
        val rejected = validateResult(resultCode, data, required, requireDocument)
        if (rejected != null) {
            val outcome = (rejected as? AndroidDocumentSelection.Rejected)?.failure?.name
            trace.emit(if (outcome == null) "cancelled" else "failed", outcome)
            return Selected.Failed(rejected)
        }
        val intent = checkNotNull(data)
        val uri = checkNotNull(intent.data)
        val persisted = persist && intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0 &&
            persistPermission(uri, required)
        trace.emit("completed", if (persisted) "PERSISTED" else "TRANSIENT")
        return Selected.Granted(uri, persisted)
    }
    private fun validateResult(resultCode: Int, data: Intent?, required: Int, requireDocument: Boolean): AndroidDocumentSelection? {
        if (resultCode != Activity.RESULT_OK) return AndroidDocumentSelection.Cancelled
        val uri = data?.data
        if (uri == null || !validContentUri(uri) || (requireDocument && !DocumentsContract.isDocumentUri(context, uri))) {
            return AndroidDocumentSelection.Rejected(DocumentSelectionFailure.INVALID_URI)
        }
        if (data.flags and required != required || !hasGrant(context, uri, required)) {
            return AndroidDocumentSelection.Rejected(DocumentSelectionFailure.ACCESS_DENIED)
        }
        return null
    }

    private fun persistPermission(uri: Uri, required: Int): Boolean = try {
        context.contentResolver.takePersistableUriPermission(uri, required)
        context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && (required != READ_WRITE || it.isWritePermission)
        }
    } catch (_: SecurityException) {
        false // A transient grant remains usable while its system owner is alive.
    }

}

private const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

sealed interface AndroidDocumentSelection {
    data object Cancelled : AndroidDocumentSelection
    /** A rejected CREATE result can still have created a document; check cleanup. */
    data class Rejected(val failure: DocumentSelectionFailure, val cleanup: DocumentCleanup? = null) : AndroidDocumentSelection
    data class Opened(val source: AndroidDocumentSource, val persisted: Boolean) : AndroidDocumentSelection
    data class Created(val destination: AndroidNewDocument, val persisted: Boolean) : AndroidDocumentSelection
}

/** A result can be consumed only once; do not persist/replay creation requests across process death. */
class AndroidCreateDocumentRequest internal constructor(
    val intent: Intent,
    private val accept: (Int, Intent?, Boolean) -> AndroidDocumentSelection,
) {
    private val used = AtomicBoolean(false)
    fun acceptResult(resultCode: Int, data: Intent?, persist: Boolean = true): AndroidDocumentSelection {
        if (!used.compareAndSet(false, true)) return AndroidDocumentSelection.Rejected(DocumentSelectionFailure.ALREADY_USED)
        return accept(resultCode, data, persist)
    }
}

enum class DocumentSelectionFailure { INVALID_URI, ACCESS_DENIED, ALREADY_USED }

/** URI access is explicit and must be stored privately; the ordinary toString never reveals it. */
class AndroidDocumentSource internal constructor(
    private val context: Context, private val uri: Uri, override val correlationId: CorrelationId,
) : DocumentSource {
    fun reference(): String = uri.toString()
    override fun toString(): String = "AndroidDocumentSource(redacted)"
    override fun open(cancellation: DocumentCancellation): InputStream {
        requireGrant(context, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return openSafInput(context.contentResolver, uri, cancellation)
    }
}

class AndroidNewDocument internal constructor(
    private val context: Context, private val uri: Uri, override val correlationId: CorrelationId,
) : DocumentNewDestination {
    private val claimed = AtomicBoolean(false)
    override fun toString(): String = "AndroidNewDocument(redacted)"
    override fun claim(): Boolean = claimed.compareAndSet(false, true)
    override fun openWrite(cancellation: DocumentCancellation): DocumentWriteStream {
        check(claimed.get())
        requireGrant(context, uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        return openSafOutput(context.contentResolver, uri, cancellation)
    }
    override fun openRead(cancellation: DocumentCancellation): InputStream {
        check(claimed.get())
        requireGrant(context, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return openSafInput(context.contentResolver, uri, cancellation)
    }
    override fun delete(): Boolean {
        check(claimed.get())
        requireGrant(context, uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        return DocumentsContract.deleteDocument(context.contentResolver, uri)
    }
}

private sealed interface Selected {
    data class Failed(val result: AndroidDocumentSelection) : Selected
    data class Granted(val uri: Uri, val persisted: Boolean) : Selected
}

private fun validContentUri(uri: Uri): Boolean = uri.scheme == "content" && !uri.authority.isNullOrEmpty()

internal fun hasGrant(context: Context, uri: Uri, flags: Int): Boolean =
    context.checkUriPermission(uri, Process.myPid(), Process.myUid(), flags) == PackageManager.PERMISSION_GRANTED

internal fun requireGrant(context: Context, uri: Uri, flags: Int) {
    if (!hasGrant(context, uri, flags)) throw SecurityException("document grant unavailable")
}

private class PickerTrace(private val logger: Logger, private val clock: MonotonicClock) {
    private val start = clock.nowNanos()
    val id = CorrelationId.parse(UUID.randomUUID().toString())
    fun emit(state: String, outcome: String? = null, phase: String = "PICKER") {
        val fields = JsonValue.Object.Builder().put("owner", "services/resources/saf")
            .put("phase", phase).put("state", state)
            .put("elapsedMs", ClockChecks.monotonicElapsedMillis(start, clock.nowNanos()) ?: 0)
        if (outcome != null) fields.put("outcome", outcome)
        try {
            logger.log(LogRecord(if (state == "failed") LogLevel.ERROR else LogLevel.INFO,
                "resources.document.grant", correlationId = id, fields = fields.build()))
        } catch (_: Exception) { }
    }
}
