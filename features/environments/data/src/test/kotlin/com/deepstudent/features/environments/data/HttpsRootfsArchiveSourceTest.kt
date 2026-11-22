package com.deepstudent.features.environments.data

import com.deepstudent.features.environments.api.EnvironmentCatalog
import com.deepstudent.features.environments.api.EnvironmentCommandException
import com.deepstudent.features.environments.api.EnvironmentFailureCode
import com.deepstudent.features.environments.api.EnvironmentPhase
import com.deepstudent.features.environments.api.EnvironmentProgress
import com.deepstudent.features.environments.api.EnvironmentStage
import com.deepstudent.features.environments.api.PackageReceipt
import com.deepstudent.features.environments.api.RootfsArchiveSource
import com.deepstudent.features.environments.api.RootfsManifest
import com.deepstudent.features.environments.backend.*
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.security.Principal
import java.security.cert.Certificate
import java.util.concurrent.CancellationException
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HttpsRootfsArchiveSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val frozenUrl = "https://raw.githubusercontent.com/AAswordman/OperitTerminalCore/" +
        "e4646680663789002b43bbfffa6e69e65c35e614/src/main/assets/ubuntu-noble-aarch64-pd-v4.18.0.tar.xz"

    @Test fun exactFrozenEndpointAndTimeoutPolicyAreLazyAndNeverFollowRedirects() {
        val response = FakeHttps(payload = byteArrayOf(1, 2, 3), length = "3")
        var opens = 0
        val source = HttpsRootfsArchiveSource({ url ->
            opens++
            assertEquals(frozenUrl, url.toString())
            assertEquals("https", url.protocol)
            response
        }, expectedBytes = 3)
        assertEquals(0, opens)
        source.open().use { assertArrayEquals(byteArrayOf(1, 2, 3), it.readBytes()) }
        assertEquals(1, opens)
        assertFalse(response.instanceFollowRedirects)
        assertEquals(15_000, response.connectTimeout)
        assertEquals(30_000, response.readTimeout)
        assertEquals("GET", response.requestMethod)
        assertEquals("identity", response.getRequestProperty("Accept-Encoding"))
        assertTrue(response.body.closed)
        assertEquals(1, response.disconnects)
        assertEquals(64_133_552L, EnvironmentCatalog.UbuntuNobleAarch64.archiveBytes)
        assertEquals("91acaa786b8e2fbba56a9fd0f8a1188cee482b5c7baeed707b29ddaa9a294daa", EnvironmentCatalog.UbuntuNobleAarch64.sha256)
    }

    @Test fun non200IncludingRedirectAndPartialContentFailsClosedWithoutSecondRequest() {
        for (status in listOf(301, 302, 303, 307, 308, 206, 404, 500)) {
            val response = FakeHttps(status = status)
            var opens = 0
            val source = HttpsRootfsArchiveSource({ opens++; response }, 3)
            val failure = assertArchiveFailure(EnvironmentFailureCode.ARCHIVE_MISSING) { source.open() }
            assertTrue(failure.failure.recoverable)
            assertEquals(1, opens)
            assertEquals(0, response.bodyOpens)
            assertTrue(response.errorBody.closed)
            assertEquals(1, response.disconnects)
        }
    }

    @Test fun malformedWrongLengthAndEncodedBodiesAreRejectedBeforeOpeningBody() {
        for (length in listOf("0", "2", "4", "-1", "bad", "3, 3")) {
            val response = FakeHttps(length = length)
            assertArchiveFailure(EnvironmentFailureCode.INVALID_ARCHIVE) {
                HttpsRootfsArchiveSource({ response }, 3).open()
            }
            assertEquals(0, response.bodyOpens)
            assertEquals(1, response.disconnects)
        }
        val compressed = FakeHttps(length = "3", encoding = "gzip")
        assertArchiveFailure(EnvironmentFailureCode.INVALID_ARCHIVE) {
            HttpsRootfsArchiveSource({ compressed }, 3).open()
        }
        assertEquals(0, compressed.bodyOpens)
        assertEquals(1, compressed.disconnects)
    }

    @Test fun absentOrDishonestContentLengthStillRequiresExactBodySize() {
        for (length in listOf<String?>(null, "3")) {
            for (payload in listOf(byteArrayOf(1, 2), byteArrayOf(1, 2, 3, 4))) {
                val response = FakeHttps(payload = payload, length = length)
                assertArchiveFailure(EnvironmentFailureCode.INVALID_ARCHIVE) {
                    HttpsRootfsArchiveSource({ response }, 3).open().use { it.readBytes() }
                }
                assertTrue(response.body.closed)
                assertEquals(1, response.disconnects)
            }
            val exact = FakeHttps(payload = byteArrayOf(1, 2, 3), length = length)
            HttpsRootfsArchiveSource({ exact }, 3).open().use { assertEquals(3, it.readBytes().size) }
            assertTrue(exact.body.closed)
            assertEquals(1, exact.disconnects)
        }
    }

    @Test fun connectHeaderBodyAndReadFailuresCloseAndAreRecoverable() {
        assertArchiveFailure(EnvironmentFailureCode.ARCHIVE_MISSING) {
            HttpsRootfsArchiveSource({ throw SocketTimeoutException("connect secret") }, 3).open()
        }
        for (stage in listOf("headers", "body", "read")) {
            val response = FakeHttps(length = "3", failAt = stage)
            val failure = assertArchiveFailure(EnvironmentFailureCode.ARCHIVE_MISSING) {
                HttpsRootfsArchiveSource({ response }, 3).open().use { it.readBytes() }
            }
            assertTrue(failure.failure.recoverable)
            assertFalse(failure.failure.message.contains("secret"))
            assertEquals(1, response.disconnects)
            if (stage == "read") assertTrue(response.body.closed) else assertTrue(response.errorBody.closed)
        }
    }

    @Test fun cancellationClosesStreamWithoutBeingTranslatedToNetworkFailure() {
        val response = FakeHttps(length = "3", failAt = "cancel")
        val failure = runCatching { HttpsRootfsArchiveSource({ response }, 3).open().use { it.read() } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(response.body.closed)
        assertEquals(1, response.disconnects)
    }

    @Test fun storeOwnsHashAndAtomicPublicationAndDeletesFailedPartials() = runBlocking {
        val payload = byteArrayOf(1, 2, 3)
        val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val manifest = EnvironmentCatalog.UbuntuNobleAarch64.copy(
            archive = EnvironmentCatalog.UbuntuNobleAarch64.archive.copy(bytes = 3, sha256 = sha),
        )
        val paths = EnvironmentPaths(temporary.root).also { it.ensureDirectories() }
        val store = FileRootfsArchiveStore(paths)
        val bad = FakeHttps(payload = byteArrayOf(1, 2, 4), length = "3")
        assertArchiveFailure(EnvironmentFailureCode.HASH_MISMATCH) {
            store.ensureVerified(manifest, HttpsRootfsArchiveSource({ bad }, 3)) { }
        }
        assertFalse(paths.archivePart(manifest).exists())
        assertFalse(paths.archive(manifest).exists())
        assertTrue(bad.body.closed)
        assertEquals(1, bad.disconnects)
        val good = FakeHttps(payload = payload, length = null)
        assertEquals(sha, store.ensureVerified(manifest, HttpsRootfsArchiveSource({ good }, 3)) { })
        assertArrayEquals(payload, paths.archive(manifest).readBytes())
        assertFalse(paths.archivePart(manifest).exists())
        assertTrue(good.body.closed)
        assertEquals(1, good.disconnects)
    }

    @Test fun closeBeforeEofStillReleasesConnectionExactlyOnce() {
        val response = FakeHttps(length = "3")
        val stream = HttpsRootfsArchiveSource({ response }, 3).open()
        assertEquals(1, stream.read())
        stream.close()
        stream.close()
        assertTrue(response.body.closed)
        assertEquals(1, response.disconnects)
        assertTrue(runCatching { stream.read() }.exceptionOrNull() is IOException)
    }

    @Test fun networkFailureLeavesRecoverableJournalAndExplicitLocalImportCanSucceed() = runBlocking {
        val payload = byteArrayOf(1, 2, 3)
        val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val original = EnvironmentCatalog.UbuntuNobleAarch64
        val manifest = original.copy(archive = original.archive.copy(bytes = 3, sha256 = sha))
        val paths = EnvironmentPaths(temporary.root).also { it.ensureDirectories() }
        val journalStore = MemoryJournalStore()
        var active: String? = null
        val ports = EnvironmentPorts(
            journalStore = journalStore,
            archiveStore = FileRootfsArchiveStore(paths),
            extractor = object : RootfsExtractor {
                override suspend fun extract(manifest: RootfsManifest, archiveToken: String, generationId: String,
                    onProgress: (EnvironmentProgress) -> Unit): String = generationId
            },
            healthChecker = object : RootfsHealthChecker {
                override suspend fun check(manifest: RootfsManifest, stagingToken: String) =
                    RootfsHealthObservation("ubuntu", "24.04", true, true, true)
            },
            activator = object : RootfsActivator {
                override suspend fun activate(manifest: RootfsManifest, stagingToken: String): String {
                    active = stagingToken
                    return stagingToken
                }
                override suspend fun currentActive(manifest: RootfsManifest): String? = active
            },
            spaceChecker = EnvironmentSpaceChecker { Long.MAX_VALUE },
            packageInstaller = object : EnvironmentPackageInstaller {
                override suspend fun install(recipe: PackageRecipe, activeGeneration: String,
                    onStage: (EnvironmentStage, String) -> Unit): PackageReceipt = error("not part of this test")
            },
        )
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = EnvironmentService(EnvironmentCoordinator(ports), null, worker)
        try {
            var networkAttempts = 0
            val failedDownload = HttpsRootfsArchiveSource({
                networkAttempts++
                throw SocketTimeoutException("network unavailable")
            }, 3)
            service.installRootfs(manifest, failedDownload)
            assertEquals(EnvironmentPhase.FAILED, service.state.value.phase)
            assertTrue(service.state.value.recoverable)
            assertEquals(EnvironmentFailureCode.ARCHIVE_MISSING, service.state.value.failure?.code)
            assertEquals(EnvironmentCheckpoint.VERIFY, journalStore.value?.checkpoint)
            assertFalse(paths.archivePart(manifest).exists())
            assertFalse(paths.archive(manifest).exists())
            assertEquals(1, networkAttempts)
            var imported = 0
            service.installRootfs(manifest, RootfsArchiveSource { imported++; ByteArrayInputStream(payload) })
            assertEquals(EnvironmentPhase.READY, service.state.value.phase)
            assertEquals(1, imported)
            assertEquals(1, networkAttempts)
            assertArrayEquals(payload, paths.archive(manifest).readBytes())
        } finally { worker.cancel() }
    }

    private inline fun assertArchiveFailure(code: EnvironmentFailureCode, block: () -> Unit): EnvironmentCommandException {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("expected EnvironmentCommandException, got $failure", failure is EnvironmentCommandException)
        return (failure as EnvironmentCommandException).also { assertEquals(code, it.failure.code) }
    }
}

private class RecordingBody(bytes: ByteArray, private val failAt: String? = null) : InputStream() {
    private val delegate = ByteArrayInputStream(bytes)
    var closed = false
    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (failAt == "cancel") throw CancellationException("cancel")
        if (failAt == "read") throw SocketTimeoutException("read secret")
        return delegate.read(buffer, offset, length)
    }
    override fun close() { closed = true; delegate.close() }
}

private class FakeHttps(
    payload: ByteArray = byteArrayOf(1, 2, 3),
    val status: Int = 200,
    val length: String? = null,
    val encoding: String? = null,
    val failAt: String? = null,
) : HttpsURLConnection(URL("https://fake.invalid/")) {
    val body = RecordingBody(payload, failAt)
    val errorBody = RecordingBody(byteArrayOf(9))
    var bodyOpens = 0
    var disconnects = 0
    override fun getResponseCode(): Int {
        if (failAt == "headers") throw SocketTimeoutException("headers secret")
        return status
    }
    override fun getHeaderField(name: String?): String? = if (name.equals("Content-Length", true)) length else null
    override fun getContentEncoding(): String? = encoding
    override fun getInputStream(): InputStream {
        bodyOpens++
        if (failAt == "body") throw IOException("body secret")
        return body
    }
    override fun getErrorStream(): InputStream = errorBody
    override fun disconnect() { disconnects++ }
    override fun usingProxy(): Boolean = false
    override fun connect() = Unit
    override fun getCipherSuite(): String = "fake"
    override fun getLocalCertificates(): Array<Certificate>? = null
    override fun getServerCertificates(): Array<Certificate> = emptyArray()
    override fun getPeerPrincipal(): Principal? = null
    override fun getLocalPrincipal(): Principal? = null
}

private class MemoryJournalStore : EnvironmentJournalStore {
    var value: EnvironmentJournal? = null
    override fun load(): EnvironmentJournal? = value
    override fun save(journal: EnvironmentJournal) { value = journal }
}
