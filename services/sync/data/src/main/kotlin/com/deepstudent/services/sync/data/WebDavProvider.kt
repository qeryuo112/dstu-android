package com.deepstudent.services.sync.data

import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.ObjectProvider
import com.deepstudent.services.sync.backend.ProviderFactory
import com.deepstudent.services.sync.backend.SyncFailure
import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WebDavProviderFactory(
    private val client: OkHttpClient = OkHttpClient(),
    private val allowLoopbackHttp: Boolean = false,
) : ProviderFactory {
    override fun open(endpoint: String, username: String, root: String, password: CharArray): ObjectProvider {
        val url = endpoint.toHttpUrlOrNull() ?: throw SyncFailure(SyncError.INVALID_CONFIGURATION)
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) {
            throw SyncFailure(SyncError.INVALID_CONFIGURATION)
        }
        if (url.scheme != "https" && !(allowLoopbackHttp && url.host in setOf("localhost", "127.0.0.1", "::1"))) {
            throw SyncFailure(SyncError.INSECURE_ENDPOINT)
        }
        val base = WebDavPaths.append(url, root, true)
        val auth = Credentials.basic(username, String(password), Charsets.UTF_8)
        return WebDavProvider(client.newBuilder().followRedirects(false).followSslRedirects(false).build(), base, auth, url, root)
    }
}

class WebDavProvider internal constructor(
    private val client: OkHttpClient,
    private val base: HttpUrl,
    private val authorization: String,
    private val endpoint: HttpUrl,
    private val root: String,
) : ObjectProvider {
    override suspend fun get(key: String): ByteArray? = getBounded(key, MAX_BYTES)

    override suspend fun getBounded(key: String, maxBytes: Int): ByteArray? = request("GET", WebDavPaths.append(base, key), null) {
        if (it.code == 404) null else { check(it); boundedDavBody(it, minOf(maxBytes, MAX_BYTES)) }
    }

    override suspend fun getPrefix(key: String, bytes: Int, objectBytes: Long): ByteArray? {
        require(bytes in 1..MAX_PROOF_BYTES && bytes <= objectBytes)
        val builder = Request.Builder().url(WebDavPaths.append(base, key)).header("Authorization", authorization)
            .header("Accept-Encoding", "identity").header("Range", "bytes=0-${bytes - 1}")
        return execute(client.newCall(builder.build())) {
            if (it.code == 404) null else { check(it); readDavPrefix(it, bytes, objectBytes) }
        }
    }

    override suspend fun replaceClaimed(key: String, observed: ByteArray, value: ByteArray): Boolean =
        mutateClaimed("PUT", key, observed, value)

    override suspend fun deleteClaimed(key: String, observed: ByteArray): Boolean =
        mutateClaimed("DELETE", key, observed, null)

    private suspend fun mutateClaimed(method: String, key: String, observed: ByteArray, value: ByteArray?): Boolean {
        val target = WebDavPaths.append(base, key)
        val snapshot = request("GET", target, null) {
            if (it.code == 404) null else { check(it); readClaimSnapshot(it, observed) }
        } ?: return false
        val builder = Request.Builder().url(target).header("Authorization", authorization)
        snapshot.tag?.let { builder.header("If-Match", it) }
        builder.method(method, value?.toRequestBody())
        return execute(client.newCall(builder.build())) {
            if (it.code in setOf(404, 409, 412)) false else { check(it); true }
        }
    }

    override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) {
        val target = WebDavPaths.append(base, key)
        if (key.isEmpty() || key.endsWith('/')) throw SyncFailure(SyncError.INVALID_CONFIGURATION)
        if (value.size > MAX_BYTES) throw SyncFailure(SyncError.LIMIT_EXCEEDED)
        ensureBase()
        val parts = key.split('/')
        for (index in 1 until parts.size) {
            val url = WebDavPaths.append(base, parts.take(index).joinToString("/"), true)
            mkdir(url)
        }
        request("PUT", target, value, ifAbsent) { check(it) }
    }

    private suspend fun ensureBase() {
        val builder = endpoint.newBuilder().encodedPath("/")
        val parts = endpoint.pathSegments.filter { it.isNotEmpty() } + WebDavPaths.segments(root, true)
        for (segment in parts) { builder.addPathSegment(segment); mkdir(builder.build()) }
    }

    private suspend fun mkdir(url: HttpUrl) {
        request("MKCOL", url, null) { if (it.code != 405) check(it) }
    }

    override suspend fun delete(key: String) {
        if (key.isEmpty() || key.endsWith('/')) throw SyncFailure(SyncError.INVALID_CONFIGURATION)
        request("DELETE", WebDavPaths.append(base, key), null) { if (it.code != 404) check(it) }
    }

    override suspend fun list(prefix: String): List<String> {
        WebDavPaths.segments(prefix, prefix.endsWith('/'))
        val queue = ArrayDeque<String>()
        val found = mutableSetOf<String>()
        val seen = mutableSetOf<String>()
        queue.add("")
        while (queue.isNotEmpty()) {
            if (seen.size >= MAX_DIRECTORIES) throw SyncFailure(SyncError.LIMIT_EXCEEDED)
            val dir = queue.removeFirst()
            if (!seen.add(dir)) throw SyncFailure(SyncError.INVALID_REPOSITORY)
            val entries = listDirectory(dir)
            addEntries(entries, queue, found, seen.size)
        }
        return found.filter { it.startsWith(prefix) }.sorted()
    }

    private suspend fun listDirectory(dir: String): List<DavEntry> {
        val url = WebDavPaths.append(base, dir, true)
        return request("PROPFIND", url, PROPFIND) {
            if (it.code == 404 && dir.isEmpty()) emptyList() else {
                if (it.code != 207) throw SyncFailure(SyncError.NETWORK)
                WebDavListing.parse(boundedDavBody(it, MAX_BYTES), base, url)
            }
        }
    }

    private fun addEntries(entries: List<DavEntry>, queue: ArrayDeque<String>, found: MutableSet<String>, seen: Int) {
        for (entry in entries) {
            if (entry.directory) queue.add(entry.key) else if (!found.add(entry.key)) {
                throw SyncFailure(SyncError.INVALID_REPOSITORY)
            }
            if (queue.size + seen + found.size >= MAX_ENTRIES) throw SyncFailure(SyncError.LIMIT_EXCEEDED)
        }
    }

    private suspend fun <T> request(method: String, url: HttpUrl, bytes: ByteArray?, ifAbsent: Boolean = false, handle: (Response) -> T): T {
        val builder = Request.Builder().url(url).header("Authorization", authorization)
        if (method == "PROPFIND") builder.header("Depth", "1")
        if (ifAbsent) builder.header("If-None-Match", "*")
        builder.method(method, bytes?.toRequestBody() ?: if (method == "PUT") ByteArray(0).toRequestBody() else null)
        return execute(client.newCall(builder.build()), handle)
    }

    private suspend fun <T> execute(call: Call, handle: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(SyncFailure(SyncError.NETWORK))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use(handle)
                    if (continuation.isActive) continuation.resume(result)
                } catch (failure: SyncFailure) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(SyncFailure(SyncError.NETWORK))
                }
            }
        })
    }

    private fun check(response: Response) {
        if (response.isSuccessful) return
        val error = when (response.code) {
            401, 403 -> SyncError.AUTHENTICATION
            409, 412 -> SyncError.ALREADY_EXISTS
            else -> SyncError.NETWORK
        }
        throw SyncFailure(error)
    }

    companion object {
        private const val MAX_BYTES = com.deepstudent.services.sync.backend.SyncObjectLimits.WIRE_BYTES
        private const val MAX_PROOF_BYTES = 64 * 1024 * 1024 + 128
        private const val MAX_DIRECTORIES = 749
        private const val MAX_ENTRIES = 10000
        private val PROPFIND = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>".toByteArray()
    }
}
