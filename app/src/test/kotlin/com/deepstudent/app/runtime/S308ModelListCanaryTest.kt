package com.deepstudent.app.runtime

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.network.HttpLimits
import com.deepstudent.platform.network.HttpResponse
import com.deepstudent.platform.network.NetworkLog
import com.deepstudent.platform.network.NetworkPhase
import com.deepstudent.platform.network.OkHttpNetworkTransport
import com.deepstudent.platform.network.ResponseHead
import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.data.FetchedVendorModel
import com.deepstudent.services.providers.data.VendorModelsAccess
import com.deepstudent.services.providers.data.VendorModelsConnection
import com.deepstudent.services.providers.data.VendorModelsRequest
import com.deepstudent.services.providers.data.vendorModelListPort
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Model-list canary: the independent catalog port fetches two real pages over local
 * HTTP and the parsed, sorted result must equal the frozen desktop golden.
 */
class S308ModelListCanaryTest {
    @Test
    fun canary() = runBlocking {
        ModelListPeer(
            page1 = """{
                "data":[{"id":"b-model","display_name":"Model B","context_length":8192},{"id":"b-model-tts-1"}],
                "has_more":true,"last_id":"b-model"
            }
            """.trimIndent(),
            page2 = """{"data":[{"id":"a-model","max_output_tokens":1024,"top_provider":{"max_completion_tokens":4096}}]}""",
        ).use { peer ->
            val network = S308ListNetwork(peer.origin, OkHttpNetworkTransport(NetworkLog { }))
            val oracle = JsonValue.parseObject(checkNotNull(javaClass.getResource("/s3-08-endpoints-oracle.json")).readText())
            val golden = oracle.obj("modelLists").obj("openai").array("models").map { fetchedModel(it as JsonValue.Object) }
            val request = VendorModelsRequest(
                VendorModelsConnection("fixture-vendor", "openai", "https://list.example.com/v1"),
                VendorModelsAccess(authModeWire = null, backupApiKeys = listOf(" backup-key ")),
            )
            val models = vendorModelListPort(network).fetch(request, null, ProviderCancellation { false })
            assertEquals(golden, models)
            assertEquals(
                listOf("/v1/models", "/v1/models?after=b-model"),
                network.requests.map { URI(it).let { uri -> uri.rawPath + (uri.rawQuery?.let { q -> "?$q" } ?: "") } },
            )
            peer.captured().let { capture ->
                assertEquals("Bearer backup-key", capture.headers["authorization"])
                assertEquals("GET", capture.method)
            }
            writeS308CanaryReceipt(models.size, network.requests.size)
        }
    }

    @Test
    fun `opencode go catalog is exact and network free`() = runBlocking {
        val network = S308ListNetwork("http://127.0.0.1:9", OkHttpNetworkTransport(NetworkLog { }))
        val oracle = JsonValue.parseObject(checkNotNull(javaClass.getResource("/s3-08-endpoints-oracle.json")).readText())
        val golden = oracle.obj("modelLists").obj("openCodeGo").array("models").map { fetchedModel(it as JsonValue.Object) }
        val models = vendorModelListPort(network).fetch(
            VendorModelsRequest(
                VendorModelsConnection("builtin-opencode-go", "opencode_go", "https://opencode.ai/inference/go/openai/v1"),
                VendorModelsAccess(authModeWire = null),
            ),
            null,
            ProviderCancellation { false },
        )
        assertEquals(golden, models)
        assertEquals(13, models.size)
        assertEquals(0, network.requests.size)
    }

    private fun fetchedModel(raw: JsonValue.Object) = FetchedVendorModel(
        id = raw.text("id"),
        label = raw.text("label"),
        contextWindow = (raw["contextWindow"] as? JsonValue.Number)?.asLongOrNull()?.toInt(),
        maxOutputTokens = (raw["maxOutputTokens"] as? JsonValue.Number)?.asLongOrNull()?.toInt(),
    )

    private fun writeS308CanaryReceipt(models: Int, requests: Int) {
        val directory = System.getenv("DSTU_S308_RECEIPT_DIR")?.let(java.nio.file.Paths::get) ?: return
        java.nio.file.Files.createDirectories(directory)
        val receipt = JsonValue.Object.Builder()
            .put("case", "model-list-canary").put("provider", "openai").put("card", "model-list").put("verified", true)
            .put(
                "details",
                JsonValue.Object.Builder().put("models", models).put("requests", requests)
                    .put("sorted", true).put("backupKeyUsed", true).build(),
            )
            .build()
        java.nio.file.Files.write(
            directory.resolve("s308-model-list-canary.json"),
            (receipt.literal() + "\n").toByteArray(Charsets.UTF_8),
        )
    }
}

/** Minimal HTTP/1.1 peer serving two scripted model pages. */
internal class ModelListPeer(private val page1: String, private val page2: String) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val capture = CompletableFuture<ModelListCapture>()
    private val worker = thread(name = "s3-08-model-list-peer", isDaemon = true) { serve() }
    val origin = "http://127.0.0.1:${server.localPort}"
    private val page = java.util.concurrent.atomic.AtomicInteger()

    suspend fun captured(): ModelListCapture = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        capture.get(5, TimeUnit.SECONDS)
    }

    private fun serve() {
        try {
            while (true) {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().buffered()
                    val request = input.readRequest()
                    val index = page.incrementAndGet()
                    val body = if (index == 1) page1 else page2
                    socket.getOutputStream().write(
                        (
                            "HTTP/1.1 200 Canary\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\nConnection: close\r\n\r\n"
                            ).toByteArray(),
                    )
                    socket.getOutputStream().write(body.toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().flush()
                    if (index == 1) capture.complete(request)
                }
            }
        } catch (failure: Exception) {
            capture.completeExceptionally(failure)
        }
    }

    override fun close() {
        server.close()
        worker.join(5_000)
    }
}

internal class ModelListCapture(val method: String, val path: String, val query: String?, val headers: Map<String, String>)

private fun BufferedInputStream.readRequest(): ModelListCapture {
    val start = nextLine().split(' ')
    check(start.size == 3)
    val headers = linkedMapOf<String, String>()
    repeat(50) {
        val header = nextLine()
        if (header.isEmpty()) {
            val (path, query) = start[1].substringBefore('?') to start[1].substringAfter('?', "")
            return ModelListCapture(start[0], path, query.ifEmpty { null }, headers)
        }
        val separator = header.indexOf(':')
        check(separator > 0)
        headers[header.substring(0, separator).lowercase()] = header.substring(separator + 1).trim()
    }
    error("too many HTTP headers")
}

private fun BufferedInputStream.nextLine(): String {
    val bytes = ByteArrayOutputStream()
    repeat(8_192) {
        val next = read()
        check(next >= 0) { "unexpected EOF in request line" }
        if (next == '\n'.code) return bytes.toString(Charsets.US_ASCII.name()).removeSuffix("\r")
        bytes.write(next)
    }
    error("HTTP line exceeds test budget")
}
