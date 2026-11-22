package com.deepstudent.services.sync.data

import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.sync.backend.PairingService
import com.deepstudent.services.sync.backend.SyncWireCodec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

internal class LegacyMarkerWebDavFixture : AutoCloseable {
    private val server = MockWebServer()
    val factory = WebDavProviderFactory(allowLoopbackHttp = true)
    val endpoint get() = server.url("/dav/").toString()
    val webdav get() = factory.open(endpoint, "fixture", "repo", "fixture-webdav".toCharArray())
    val objects = ConcurrentHashMap<String, ByteArray>()
    val marker get() = objects.getValue(PairingService.ENCRYPTION_MARKER_KEY)
    val writes = CopyOnWriteArrayList<String>()
    val ranges = CopyOnWriteArrayList<Pair<String, Int>>()
    val matchedWrites = CopyOnWriteArrayList<String>()
    var badRange = false
    var stealMarkerAfterConditionalRead: ByteArray? = null
    var markerReadsBeforeTheft: Int = Int.MAX_VALUE

    init {
        objects[PairingService.FORMAT_KEY] = PairingService.FORMAT.toByteArray()
        objects[PairingService.INSTANCE_KEY] = """{"instance_id":"same-instance","format_version":4}""".toByteArray()
        objects[PairingService.ENCRYPTION_MARKER_KEY] = legacy.copyOf()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = serve(request)
        }
        server.start()
    }

    fun seedBackups(vararg backups: Pair<String, ByteArray>) {
        val versions = backups.mapIndexed { index, (id, bytes) ->
            objects["backups/$id.zip"] = bytes
            val day = (10 - index).toString().padStart(2, '0')
            JsonValue.Object.Builder().put("id", id).put("timestamp", "2026-01-${day}T00:00:00Z")
                .put("size", bytes.size).put("checksum", Digests.sha256Hex(bytes)).put("deviceId", "previous-desktop").build()
        }
        objects["manifests/${SyncWireCodec.deviceHash("previous-desktop")}.json"] = JsonValue.Object.Builder()
            .put("version", 1).put("latest", backups.first().first).put("updatedAt", "2026-01-10T00:00:00Z")
            .put("versions", JsonValue.array(versions)).build().literal().toByteArray()
    }

    private fun serve(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty().trimEnd('/')
        if (request.method == "MKCOL") return status(405)
        if (path != ROOT && !path.startsWith("$ROOT/")) return status(404)
        val key = path.removePrefix(ROOT).removePrefix("/")
        return synchronized(this) {
            when (request.method) {
                "PROPFIND" -> listing(key)
                "GET" -> get(request, key)
                "PUT", "DELETE" -> mutate(request, key)
                else -> status(405)
            }
        }
    }

    private fun get(request: RecordedRequest, key: String): MockResponse {
        val value = objects[key] ?: return status(404)
        val range = request.getHeader("Range")
        if (range != null) {
            val requested = range.removePrefix("bytes=0-").toInt() + 1
            ranges += key to requested
            if (requested > value.size) return status(416)
            val start = if (badRange) 1 else 0
            return status(206).setHeader("Content-Range", "bytes $start-${requested - 1}/${value.size}")
                .setBody(Buffer().write(value, 0, requested))
        }
        if (key == PairingService.ENCRYPTION_MARKER_KEY && --markerReadsBeforeTheft == 0) {
            stealMarkerAfterConditionalRead?.let { objects[key] = it.copyOf() }
        }
        return status(200).setHeader("ETag", tag(value)).setBody(Buffer().write(value))
    }

    private fun mutate(request: RecordedRequest, key: String): MockResponse {
        val old = objects[key]
        if (request.getHeader("If-None-Match") == "*" && old != null) return status(412)
        request.getHeader("If-Match")?.let { match ->
            matchedWrites += key
            if (old == null || tag(old) != match) return status(412)
        }
        if (request.method == "DELETE") return status(if (objects.remove(key) == null) 404 else 204)
        objects[key] = request.body.readByteArray()
        writes += key
        return status(if (old == null) 201 else 204)
    }

    private fun listing(directory: String): MockResponse {
        val prefix = directory.takeIf(String::isNotEmpty)?.plus('/') ?: ""
        val base = if (directory.isEmpty()) ROOT else "$ROOT/$directory"
        val children = objects.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
            .map { if ('/' in it) it.substringBefore('/') + '/' else it }.toSortedSet()
        val responses = xmlEntry("$base/", true) + children.joinToString("") { xmlEntry("$base/$it", it.endsWith('/')) }
        return status(207).setBody("<d:multistatus xmlns:d='DAV:'>$responses</d:multistatus>")
    }

    private fun xmlEntry(path: String, dir: Boolean): String = "<d:response><d:href>$path</d:href><d:propstat><d:prop>" +
        "<d:resourcetype>${if (dir) "<d:collection/>" else ""}</d:resourcetype></d:prop>" +
        "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"

    private fun tag(value: ByteArray) = "\"${Digests.sha256Hex(value)}\""
    private fun status(code: Int) = MockResponse().setResponseCode(code)
    override fun close() = server.close()

    companion object {
        private const val ROOT = "/dav/repo"
        val legacy = """{"version":1,"createdByDevice":"frozen-desktop-writer","createdAt":"2026-01-01T12:00:00Z"}""".toByteArray()
    }
}
