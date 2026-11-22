package com.deepstudent.app.backup

import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.engines.zstd.ZstdEngine
import com.deepstudent.services.sync.backend.PairingService
import com.deepstudent.services.sync.backend.SyncBatch
import com.deepstudent.services.sync.backend.SyncBatchCodec
import com.deepstudent.services.sync.backend.SyncWireCodec
import com.deepstudent.services.sync.data.WebDavProviderFactory
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/** Real TLS, basic credentials and atomic claims, on a disposable repository only. */
internal class ManagedContinuityTestRepository : AutoCloseable {
    private val fixture = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        .resolve("s215-managed/fixture-tls.p12")
    private val transport = fixtureTransport(fixture)
    val providerFactory = WebDavProviderFactory(transport.client)
    private val objects = mutableMapOf<String, ByteArray>()
    private val server = MockWebServer()
    private var oldRevoked = false
    private var loseClaimReply = false
    private var conflictAfterClaim = false
    private val oldAuthorization = Credentials.basic(USER, OLD_PASSWORD)
    private val newAuthorization = Credentials.basic(USER, NEW_PASSWORD)
    val url: String get() = server.url("/repo/").toString()

    init {
        objects["/repo/${PairingService.FORMAT_KEY}"] = PairingService.FORMAT.toByteArray()
        objects["/repo/${PairingService.INSTANCE_KEY}"] = """{"format_version":4,"instance_id":"$INSTANCE"}""".toByteArray()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
        }
        server.useHttps(transport.tls.socketFactory, false)
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @Synchronized
    fun revokeOldAndRecordSequence() {
        check(!oldRevoked)
        // Valid v4+zstd wire bytes, but seeded by the fixture, not published by an old device.
        objects["/repo/${changeKey(5)}"] = batch(5, "frozen-fixture")
        oldRevoked = true
    }

    @Synchronized
    fun simulateSequenceDrift() { objects["/repo/${changeKey(6)}"] = batch(6, "frozen-fixture") }

    @Synchronized
    fun simulateSequenceConflict() { objects["/repo/${changeKey(5, "conflicting-fixture")}"] = batch(5, "conflicting-fixture") }

    @Synchronized
    fun loseNextClaimReply() { loseClaimReply = true }

    @Synchronized
    fun introduceConflictAfterClaim() { conflictAfterClaim = true }

    @Synchronized
    fun claimCount() = objects.keys.count { it.startsWith(CLAIMS) }

    @Synchronized
    fun hasPublishedSequence(sequence: Long): Boolean {
        val suffix = "/${SyncWireCodec.deviceHash(WRITER)}/${sequence.toString().padStart(12, '0')}-"
        return listOf("changes", "v4/shards").any { type ->
            objects.keys.any { it.startsWith("/repo/data_governance/$type$suffix") }
        }
    }

    private fun changeKey(seq: Long, operation: String = "frozen-fixture") =
        SyncWireCodec.changeKey(WRITER, seq.toULong(), 1700000000u, operation, true)

    private fun batch(seq: Long, operation: String): ByteArray = ZstdEngine().encode(
        SyncBatchCodec.encode(SyncBatch(emptyList(), WRITER, 4u, seq.toULong(), WRITER, operation)),
    )

    @Synchronized
    private fun handle(request: RecordedRequest): MockResponse {
        val auth = request.getHeader("Authorization")
        if (auth != newAuthorization && (auth != oldAuthorization || oldRevoked)) return response(403)
        val path = request.path ?: return response(400)
        if (path == "/repo" && request.method == "MKCOL") return response(405)
        if (!path.startsWith("/repo/")) return response(404)
        return when (request.method) {
            "MKCOL" -> response(405) // The management namespace is provisioned by this authority.
            "GET" -> objects[path]?.let { response(200).setBody(okio.Buffer().write(it)) } ?: response(404)
            "PROPFIND" -> multistatus(path)
            "PUT" -> put(path, request)
            "DELETE" -> releaseLease(path)
            else -> response(405)
        }
    }

    private fun releaseLease(path: String): MockResponse {
        if (!path.startsWith("/repo/data_governance/locks/sync-target/")) return response(403)
        objects.remove(path)
        return response(204)
    }

    private fun put(path: String, request: RecordedRequest): MockResponse {
        val ifAbsent = request.getHeader("If-None-Match") == "*"
        if (path.startsWith(CLAIMS) && (!ifAbsent || !oldRevoked)) return response(403)
        if (ifAbsent && objects.containsKey(path)) return response(412)
        objects[path] = request.body.readByteArray()
        if (path.startsWith(CLAIMS)) {
            if (conflictAfterClaim) {
                conflictAfterClaim = false
                objects["/repo/${changeKey(5, "conflicting-fixture")}"] = batch(5, "conflicting-fixture")
            }
            if (loseClaimReply) {
                loseClaimReply = false
                return response(503) // The claim is durable; the client cannot infer this from the response.
            }
        }
        return response(201)
    }

    private fun multistatus(path: String): MockResponse {
        val directory = path.trimEnd('/') + "/"
        val children = mutableSetOf<String>()
        for (key in objects.keys.filter { it.startsWith(directory) }) {
            val remainder = key.removePrefix(directory)
            val type = if ('/' in remainder) "/" else ""
            children += directory + remainder.substringBefore('/') + type
        }
        val body = (listOf(directory) + children.sorted()).joinToString("", "<d:multistatus xmlns:d=\"DAV:\">", "</d:multistatus>", transform = ::entry)
        return response(207).setHeader("Content-Type", "application/xml; charset=utf-8").setBody(body)
    }

    private fun entry(path: String): String {
        val resource = if (path.endsWith('/')) "<d:collection/>" else ""
        return "<d:response><d:href>$path</d:href><d:propstat><d:prop><d:resourcetype>$resource</d:resourcetype>" +
            "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
    }

    override fun close() { server.shutdown() }

    companion object {
        const val USER = "fixture"
        const val NEW_PASSWORD = "temporary-destination-fixture"
        const val OLD_PASSWORD = "retired-source-fixture"
        const val INSTANCE = "default"
        const val WRITER = "device-7f3a91c2"
        const val PEER = "device-91bc4de0"
        private const val CLAIMS = "/repo/dstu-management/continuity-claims/v1/"
        private fun response(code: Int) = MockResponse().setResponseCode(code)
    }
}

private data class FixtureTransport(val tls: SSLContext, val client: OkHttpClient)

private fun fixtureTransport(file: File): FixtureTransport {
    val password = "dstu-test-only".toCharArray()
    val keys = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, password) } }
    val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
    password.fill('\u0000')
    val alias = keys.aliases().toList().single()
    val trusted = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("fixture", keys.getCertificate(alias)) }
    val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trusted) }
    val manager = trust.trustManagers.filterIsInstance<X509TrustManager>().single()
    val tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, arrayOf(manager), null) }
    return FixtureTransport(tls, OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, manager).build())
}
