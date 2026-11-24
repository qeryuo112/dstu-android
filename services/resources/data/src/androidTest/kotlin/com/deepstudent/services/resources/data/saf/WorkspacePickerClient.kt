package com.deepstudent.services.resources.data.saf

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.ParcelFileDescriptor.AutoCloseOutputStream
import android.os.Process
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants

/** Independent UID. Only operates on the isolated app authority and synthetic fixture documents. */
class WorkspacePickerClient : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (callingPackage != TEST_APP) {
            finish()
            return
        }
        when (intent.getStringExtra("case")) {
            "create" -> start(
                REQUEST_CREATE,
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, title())
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildRootUri(AUTHORITY, "workspace"))
                },
            )

            "probe" -> finishCheck(::probe)

            "probeFreshGrant" -> finishCheck(::probe)

            else -> finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun start(request: Int, picker: Intent) {
        picker.setPackage("com.android.documentsui")
        // Set the exact system component: package-only OPEN can be redirected on OEM builds.
        val found = packageManager.queryIntentActivities(picker, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo }.singleOrNull { it.packageName == "com.android.documentsui" && it.exported }
        if (found == null) {
            finish()
            return
        }
        picker.setClassName(found.packageName, found.name)
        startActivityForResult(picker, request)
    }

    @Deprecated("Activity result needed for API 26 fixture")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CREATE || resultCode != RESULT_OK || data?.data == null) {
            finish()
            return
        }
        val uri = requireNotNull(data.data)
        finishCheck { writeAndRead(uri, data.flags) }
    }

    private fun writeAndRead(uri: Uri, flags: Int): Intent {
        require(uri.authority == AUTHORITY && DocumentsContract.getDocumentId(uri).startsWith("workspace:att_ws_"))
        require(flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0 && flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val name = title()
        val actualTitle = contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)!!.use {
            check(it.moveToFirst())
            it.getString(0)
        }
        require(actualTitle == name)
        val pfd = requireNotNull(contentResolver.openFileDescriptor(uri, "w"))
        val bytes = fixtureBytes(name)
        AutoCloseOutputStream(pfd).use {
            it.write(bytes)
            it.fd.sync()
            it.fd.sync()
        }
        check(contentResolver.openInputStream(uri)!!.use { it.readBytes() }.contentEquals(bytes))
        val lease = holdWorkingHandle(uri, bytes)
        return Intent().putExtra("document", uri.toString()).putExtra("flags", flags).putExtra("lease", lease)
    }

    private fun holdWorkingHandle(uri: Uri, bytes: ByteArray): String {
        val descriptor = requireNotNull(contentResolver.openFileDescriptor(uri, "rw"))
        try {
            val received = ByteArray(1)
            check(Os.pread(descriptor.fileDescriptor, received, 0, 1, 0) == 1)
            check(received[0] == bytes[0])
            // Stage, but do not commit, different bytes. Closing a revoked handle must not publish them.
            check(Os.pwrite(descriptor.fileDescriptor, byteArrayOf(0x58), 0, 1, 0) == 1)
            val token = java.util.UUID.randomUUID().toString()
            synchronized(leases) { check(leases.put(token, descriptor) == null) }
            return token
        } catch (failure: Throwable) {
            descriptor.close()
            throw failure
        }
    }

    private fun probe(): Intent {
        val document = requireNotNull(intent.getStringExtra("document"))
        val uri = Uri.parse(document)
        require(uri.authority == AUTHORITY && DocumentsContract.getDocumentId(uri).startsWith("workspace:"))
        val readMode = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val writeMode = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val readGranted = checkUriPermission(uri, Process.myPid(), Process.myUid(), readMode) == PackageManager.PERMISSION_GRANTED
        val writeGranted = checkUriPermission(uri, Process.myPid(), Process.myUid(), writeMode) == PackageManager.PERMISSION_GRANTED
        val reply = Intent().putExtra("readGranted", readGranted).putExtra("writeGranted", writeGranted)
            .putExtra("readDenied", openDenied(uri, "r")).putExtra("writeDenied", openDenied(uri, "rw"))
        val token = intent.getStringExtra("lease") ?: return reply
        val descriptor = synchronized(leases) { leases.remove(token) } ?: error("existing descriptor not retained")
        descriptor.use { fd ->
            val bytes = ByteArray(1)
            val cachedRead = outcome { Os.pread(fd.fileDescriptor, bytes, 0, 1, 0) }
            val unread = outcome { Os.pread(fd.fileDescriptor, bytes, 0, 1, UNREAD_OFFSET) }
            val sync = outcome { Os.fsync(fd.fileDescriptor) }
            val write = outcome { Os.pwrite(fd.fileDescriptor, bytes, 0, 1, 0) }
            reply.putExtra("existingRead", unread).putExtra("existingCachedRead", cachedRead)
                .putExtra("existingWritten", write).putExtra("existingSync", sync)
        }
        return reply
    }

    private fun openDenied(uri: Uri, mode: String): Boolean =
        runCatching { contentResolver.openFileDescriptor(uri, mode)?.close() }.exceptionOrNull() is SecurityException

    private fun outcome(operation: () -> Unit): String = try {
        operation()
        "returned"
    } catch (failure: ErrnoException) {
        if (failure.errno == OsConstants.EACCES) "denied" else "errno_${failure.errno}"
    } catch (failure: Exception) {
        failure.javaClass.simpleName
    }

    private fun finishCheck(operation: () -> Intent) {
        Thread({
            val result = try {
                operation().putExtra("passed", true)
            } catch (failure: Throwable) {
                Intent().putExtra("passed", false).putExtra("error", failure.javaClass.simpleName)
            }
            runOnUiThread {
                setResult(RESULT_OK, result.putExtra("uid", Process.myUid()))
                finish()
            }
        }, "s228-fixture").start()
    }

    private fun fixtureBytes(title: String): ByteArray {
        val prefix = "S228 fixture $title".toByteArray()
        return ByteArray(FIXTURE_BYTES) { index -> if (index < prefix.size) prefix[index] else (index % 251).toByte() }
    }

    private fun title(): String = requireNotNull(intent.getStringExtra("title")).also {
        require(it.matches(Regex("DSTU-S228-[a-z0-9]+\\.txt")))
    }

    private companion object {
        const val TEST_APP = "com.deepstudent.app.s2device"
        const val AUTHORITY = "$TEST_APP.documents.workspace"
        const val REQUEST_CREATE = 228
        const val FIXTURE_BYTES = 512 * 1024
        const val UNREAD_OFFSET = 448L * 1024
        val leases = mutableMapOf<String, ParcelFileDescriptor>()
    }
}
