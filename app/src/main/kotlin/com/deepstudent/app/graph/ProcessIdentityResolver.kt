package com.deepstudent.app.graph

import android.app.Application
import android.os.Build
import android.os.Process
import java.io.FileInputStream

internal interface ProcessNamePlatform {
    val sdkInt: Int

    fun applicationProcessName(): String?

    fun procCmdline(): String?
}

internal class AndroidProcessNamePlatform(
    @Suppress("UNUSED_PARAMETER") application: Application,
) : ProcessNamePlatform {
    override val sdkInt: Int
        get() = Build.VERSION.SDK_INT

    override fun applicationProcessName(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            null
        }

    override fun procCmdline(): String? = ProcCmdlineReader.read()
}

object AndroidProcessIdentityResolver {
    fun resolve(application: Application): ProcessIdentity =
        resolveProcessIdentity(
            applicationId = application.packageName,
            platform = AndroidProcessNamePlatform(application),
            processUid = Process.myUid(),
            appUid = application.applicationInfo.uid,
            processIsIsolated = currentProcessIsIsolated(),
        )

    private fun currentProcessIsIsolated(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && Process.isIsolated()
}

internal fun resolveProcessIdentity(
    applicationId: String,
    platform: ProcessNamePlatform,
    processUid: Int,
    appUid: Int,
    processIsIsolated: Boolean = false,
): ProcessIdentity {
    val isolatedUid = processIsIsolated || isIsolatedUid(processUid, appUid)

    // API 28+ uses the public Application.getProcessName(). API 26/27 must not
    // call it because the method does not exist on those platform levels.
    if (platform.sdkInt >= 28) {
        val applicationName = platform.applicationProcessName()
        if (!applicationName.isNullOrBlank()) {
            return identityFor(
                applicationId = applicationId,
                processName = applicationName,
                source = ProcessNameSource.APPLICATION_API,
                isolatedUid = isolatedUid,
            )
        }
    }

    val procName = platform.procCmdline()
    if (!procName.isNullOrBlank()) {
        return identityFor(
            applicationId = applicationId,
            processName = procName,
            source = ProcessNameSource.PROC_CMDLINE,
            isolatedUid = isolatedUid,
        )
    }

    val fallbackRole = if (isolatedUid) ProcessRole.ISOLATED else ProcessRole.AUXILIARY
    return ProcessIdentity(
        processName = null,
        role = fallbackRole,
        source = ProcessNameSource.UNAVAILABLE,
        isIsolatedUid = isolatedUid,
    )
}

internal fun isIsolatedUid(processUid: Int, appUid: Int): Boolean {
    if (processUid == appUid) return false
    if (processUid / USER_RANGE != appUid / USER_RANGE) return false
    val appId = processUid % USER_RANGE
    return appId in FIRST_ISOLATED_UID..LAST_ISOLATED_UID
}

private fun identityFor(
    applicationId: String,
    processName: String,
    source: ProcessNameSource,
    isolatedUid: Boolean,
): ProcessIdentity {
    val classified = ProcessClassifier.classify(applicationId, processName, source)
    val role = if (isolatedUid) ProcessRole.ISOLATED else classified.role
    return classified.copy(role = role, isIsolatedUid = isolatedUid)
}

private const val USER_RANGE = 100_000
private const val FIRST_ISOLATED_UID = 90_000
private const val LAST_ISOLATED_UID = 99_999

internal object ProcCmdlineReader {
    private const val CMDLINE_PATH = "/proc/self/cmdline"
    private const val MAX_CMDLINE_BYTES = 4096

    fun read(): String? = try {
        FileInputStream(CMDLINE_PATH).use { stream ->
            val buffer = ByteArray(MAX_CMDLINE_BYTES)
            val read = stream.read(buffer)
            if (read <= 0) null else parse(buffer, read)
        }
    } catch (_: Exception) {
        null
    }

    internal fun parse(bytes: ByteArray, length: Int = bytes.size): String? {
        if (length <= 0) return null
        val limit = minOf(length, bytes.size)
        var end = 0
        while (end < limit && bytes[end] != 0.toByte()) {
            end++
        }
        if (end == 0) return null
        return String(bytes, 0, end, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
    }
}
