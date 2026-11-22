package com.deepstudent.services.resources.data.saf

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** A test DocumentsProvider, not an implementation or evidence of any commercial cloud service. */
class SafFixtureProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: ROOT_COLUMNS
        val values = mapOf(DocumentsContract.Root.COLUMN_ROOT_ID to "root",
            DocumentsContract.Root.COLUMN_TITLE to "S2.07 test fixture",
            DocumentsContract.Root.COLUMN_DOCUMENT_ID to "root",
            DocumentsContract.Root.COLUMN_FLAGS to DocumentsContract.Root.FLAG_SUPPORTS_CREATE)
        return MatrixCursor(columns).apply { addRow(columns.map { values[it] }) }
    }

    override fun queryDocument(id: String, projection: Array<out String>?): Cursor {
        if (id != "root" && !SafFixture.entries.containsKey(id)) throw FileNotFoundException()
        val columns = projection ?: DOC_COLUMNS
        val directory = id == "root"
        val values = mapOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID to id,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME to if (directory) "fixture" else "fixture-document",
            DocumentsContract.Document.COLUMN_MIME_TYPE to if (directory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream",
            DocumentsContract.Document.COLUMN_FLAGS to if (directory) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else
                DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE)
        return MatrixCursor(columns).apply { addRow(columns.map { values[it] }) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: DOC_COLUMNS)

    override fun getDocumentType(documentId: String): String =
        if (documentId == "root") DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        require(parentDocumentId == "root")
        require(ID.matches(displayName))
        check(SafFixture.entries.putIfAbsent(displayName, Entry(displayName)) == null)
        if (displayName.startsWith("local-")) localFile(displayName).createNewFile()
        return displayName
    }

    override fun deleteDocument(documentId: String) {
        val entry = SafFixture.entry(documentId)
        entry.deleteAttempts.incrementAndGet()
        if (documentId.startsWith("deny-delete-")) throw SecurityException("fixture refuses deletion")
        entry.gate.countDown()
        entry.writeDone?.let { if (!it.await(20, TimeUnit.SECONDS)) throw IOException("upload not settled") }
        if (!documentId.startsWith("local-")) {
            val connection = SafFixture.connection(documentId, "DELETE")
            try { if (connection.responseCode != 204) throw IOException("fixture delete failed") }
            finally { connection.disconnect() }
        } else {
            localFile(documentId).delete()
        }
        SafFixture.entries.remove(documentId)
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val entry = SafFixture.entry(documentId)
        if (mode != "r" && mode != "w") throw FileNotFoundException("only narrow modes supported")
        if (documentId.startsWith("local-")) {
            val file = localFile(documentId)
            return ParcelFileDescriptor.open(file, if (mode == "r") ParcelFileDescriptor.MODE_READ_ONLY else
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY)
        }
        return if (mode == "r") openRead(entry, signal) else openWrite(entry)
    }

    private fun openRead(entry: Entry, signal: CancellationSignal?): ParcelFileDescriptor {
        entry.writeDone?.let { done ->
            while (!done.await(100, TimeUnit.MILLISECONDS)) {
                signal?.throwIfCanceled()
            }
            if (entry.writeFailed) throw FileNotFoundException("fixture upload failed")
        }
        val pair = ParcelFileDescriptor.createReliablePipe()
        thread(name = "s207-provider-get", isDaemon = true) {
            val out = pair[1]
            try {
                if (entry.id.startsWith("stall-read-")) {
                    entry.waiting.countDown()
                    entry.gate.await(20, TimeUnit.SECONDS)
                }
                val head = SafFixture.connection(entry.id, "HEAD")
                val size = try {
                    if (head.responseCode != 200 || head.contentLengthLong < 0) throw IOException("fixture HEAD failed")
                    head.contentLengthLong
                } finally { head.disconnect() }
                val connection = SafFixture.connection(entry.id, "GET")
                try {
                    if (connection.responseCode != 200 || connection.contentLengthLong != size) {
                        throw IOException("fixture GET metadata mismatch")
                    }
                    var count = 0L
                    val stream = FileOutputStream(out.fileDescriptor)
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            stream.write(buffer, 0, n)
                            count += n
                        }
                    }
                    if (count != size) throw IOException("fixture GET truncated")
                    out.close()
                } finally { connection.disconnect() }
            } catch (_: Exception) { try { out.closeWithError("fixture GET failed") } catch (_: Exception) { } }
        }
        return pair[0]
    }

    private fun openWrite(entry: Entry): ParcelFileDescriptor {
        val pair = ParcelFileDescriptor.createReliablePipe()
        val done = CountDownLatch(1)
        entry.writeDone = done
        thread(name = "s207-provider-put", isDaemon = true) {
            val input = pair[0]
            try {
                if (entry.id.startsWith("stall-write-")) {
                    entry.waiting.countDown()
                    entry.gate.await(20, TimeUnit.SECONDS)
                }
                val fail = entry.id.startsWith("fail-put-")
                val connection = SafFixture.connection(entry.id, "PUT", fail)
                try {
                    connection.doOutput = true
                    connection.setChunkedStreamingMode(16 * 1024)
                    val upload = connection.outputStream
                    val file = FileInputStream(input.fileDescriptor)
                    val hash = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val n = file.read(buffer)
                        if (n < 0) break
                        upload.write(buffer, 0, n)
                        hash.update(buffer, 0, n)
                    }
                    // An aborted reliable pipe must never be sent as a successful complete PUT.
                    input.checkError()
                    upload.close()
                    if (connection.responseCode != 201) throw IOException("fixture PUT failed")
                    val expected = hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    if (connection.getHeaderField("X-Fixture-Content-Sha256") != expected) throw IOException("fixture digest mismatch")
                    input.close()
                } finally { connection.disconnect() }
            } catch (_: Exception) {
                entry.writeFailed = true
                try { input.closeWithError("fixture PUT failed") } catch (_: Exception) { }
            } finally { done.countDown() }
        }
        return pair[1]
    }

    private fun localFile(id: String): File = File(requireNotNull(context).cacheDir, id)

    companion object {
        private val ID = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}")
        private val ROOT_COLUMNS = arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_FLAGS)
        private val DOC_COLUMNS = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS)
    }
}

internal class Entry(val id: String) {
    val deleteAttempts = AtomicInteger()
    val waiting = CountDownLatch(1)
    val gate = CountDownLatch(1)
    @Volatile var writeDone: CountDownLatch? = null
    @Volatile var writeFailed = false
}

internal object SafFixture {
    const val AUTHORITY = "com.deepstudent.services.resources.data.test.saf"
    val entries = ConcurrentHashMap<String, Entry>()
    fun entry(id: String) = entries[id] ?: throw FileNotFoundException()
    fun connection(id: String, method: String, failPath: Boolean = false): HttpURLConnection {
        val raw = InstrumentationRegistry.getArguments().getString("s207CloudPort") ?: "18765"
        val port = raw.toInt().also { require(it in 1..65535) }
        val suffix = if (failPath) "/fault" else ""
        return (URL("http://127.0.0.1:$port/objects/$id$suffix").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000
            readTimeout = 15000
        }
    }
}
