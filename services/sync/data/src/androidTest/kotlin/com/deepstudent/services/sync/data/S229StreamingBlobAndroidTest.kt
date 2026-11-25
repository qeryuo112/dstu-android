package com.deepstudent.services.sync.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.core.common.id.Digests
import com.deepstudent.services.sync.backend.ObjectProvider
import com.deepstudent.services.sync.backend.transfer.BlobTransfer
import com.deepstudent.services.sync.backend.transfer.SyncBlob
import com.deepstudent.services.sync.backend.transfer.SyncBlobs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class S229StreamingBlobAndroidTest {
    @get:Rule val s229Foreground = S229PublicationForegroundRule()

    @Test
    fun cancellationDuringHttpBodyRemovesStageAndRetryInstallsOnlyVerifiedBytes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "s229-blob-${UUID.randomUUID()}")
        val body = ByteArray(256 * 1024) { (it % 237).toByte() }
        val hash = Digests.sha256Hex(body)
        val path = "${hash.take(2)}/$hash.bin"
        val started = CompletableDeferred<Unit>()
        val remoteIndex = S229MemoryProvider().apply {
            objects["data_governance/blobs_manifest.json"] = (
                """{"entries":{"$hash":{"relative_path":"$path","size":${body.size},""" +
                    """"updated_at":"2026-11-25T00:00:00Z"}},"updated_at":""}"""
                ).toByteArray()
        }
        val local = ReceiveOnlyBlobs()
        try {
            MockWebServer().use { server ->
                val webDav = WebDavProviderFactory(allowLoopbackHttp = true)
                    .open(server.url("/").toString(), "public-fixture", "fixture", charArrayOf())
                val remote = WebDavBlobReader(remoteIndex, webDav, started)
                val transfer = BlobTransfer(remote, local, stageDirectory = directory)
                server.enqueue(newBody(body).throttleBody(1024, 200, TimeUnit.MILLISECONDS))
                val job = launch { transfer.synchronize() }
                try {
                    withTimeout(10_000) { started.await() }
                    withTimeout(5_000) { job.cancelAndJoin() }
                } finally {
                    job.cancel()
                }
                assertEquals(0, local.installs)
                assertTrue(directory.list()?.isEmpty() == true)
                assertEquals("GET", server.takeRequest(2, TimeUnit.SECONDS)?.method)

                server.enqueue(newBody(body))
                transfer.synchronize()
                assertEquals("GET", server.takeRequest(2, TimeUnit.SECONDS)?.method)
                assertEquals(1, local.installs)
                assertArrayEquals(body, local.bytes)
                assertTrue(directory.list()?.isEmpty() == true)
                assertEquals(0, remoteIndex.puts)
            }
        } finally {
            check(directory.deleteRecursively())
        }
    }

    private fun newBody(bytes: ByteArray) = MockResponse().setBody(Buffer().write(bytes))
}

private class WebDavBlobReader(
    remoteIndex: ObjectProvider,
    private val webDav: ObjectProvider,
    private val started: CompletableDeferred<Unit>,
) : ObjectProvider by remoteIndex {
    override suspend fun <T> readStream(key: String, limit: Int, read: (InputStream) -> T): T? = webDav.readStream(key, limit) { input ->
        read(object : FilterInputStream(input) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, length).also {
                if (it > 0) started.complete(Unit)
            }
        })
    }
}

private class ReceiveOnlyBlobs : SyncBlobs {
    var installs = 0
        private set
    var bytes: ByteArray? = null
        private set

    override fun list(): List<SyncBlob> = emptyList()
    override fun read(blob: SyncBlob): ByteArray? = error("expected streaming read")
    override fun install(blob: SyncBlob, bytes: ByteArray): Unit = error("expected streaming install")
    override fun copyTo(blob: SyncBlob, output: OutputStream, check: () -> Unit): Boolean = error("expected no upload")

    override fun installFrom(blob: SyncBlob, input: InputStream, check: () -> Unit) {
        check()
        bytes = input.readBytes()
        installs++
    }
}
