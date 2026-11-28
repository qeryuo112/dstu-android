package com.deepstudent.app.runtime

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.DstuApplication
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.chat.api.ChatStreamPhase
import com.deepstudent.features.chat.api.ChatStreamRequest
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.runtime.api.RuntimeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class S303TurnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as DstuApplication
    private val restartMarker get() = File(app.filesDir, "s303-live-turn")

    @Test fun completedRequestPersistsBlockAndUsageBeforeRuntimeReceipt(): Unit = runBlocking {
        val runtime = app.runtime()
        withPeer(response(FIRST + SECOND + USAGE + DONE)) { peer ->
            val task = start(runtime, peer)
            val recorded = checkNotNull(peer.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("POST", recorded.method)
            assertEquals("/v1/chat/completions", recorded.path)
            val body = JsonValue.parseObject(recorded.body.readUtf8())
            assertEquals(JsonValue.text("fixture-chat"), body["model"])
            val messages = checkNotNull(body["messages"] as? JsonValue.Array)
            assertEquals(JsonValue.text("本机请求"), (messages.values.single() as JsonValue.Object)["content"])
            val snapshot = withTimeout(10_000) { runtime.chatStreams.observe(task).first { it.phase != ChatStreamPhase.Running } }
            assertEquals(ChatStreamPhase.Completed, snapshot.phase)
            assertEquals("第一段👩‍💻", snapshot.text)
            val settled = checkNotNull(runtime.controller.snapshot(task))
            assertNotNull(settled.execution.businessFactId)
            assertEquals("第一段👩‍💻", assistant(task).details.content)
            assertEquals("success", assistant(task).status)
            assertEquals(listOf(listOf(11L, 4L, 15L, 3L)), usage(task))
            runtime.startup.recover()
            assertEquals(1, usage(task).size)
        }
    }

    @Test fun disconnectedRequestKeepsPartialReply(): Unit = runBlocking {
        val runtime = app.runtime()
        val interrupted = response(FIRST + "data: " + "x".repeat(2_048))
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        withPeer(interrupted) { peer ->
            val task = start(runtime, peer)
            val snapshot = withTimeout(10_000) { runtime.chatStreams.observe(task).first { it.phase != ChatStreamPhase.Running } }
            assertEquals(ChatStreamPhase.Failed, snapshot.phase)
            assertEquals("第一段", snapshot.text)
            assertEquals("第一段", assistant(task).details.content)
            assertEquals("error", assistant(task).status)
            assertTrue(usage(task).isEmpty())
        }
    }

    @Test fun incompleteUsageDoesNotBlockCompletionOrCreateAnEstimatedRow(): Unit = runBlocking {
        val runtime = app.runtime()
        withPeer(response(FIRST + PARTIAL_USAGE + DONE)) { peer ->
            val task = start(runtime, peer)
            val snapshot = withTimeout(10_000) { runtime.chatStreams.observe(task).first { it.phase != ChatStreamPhase.Running } }
            assertEquals(ChatStreamPhase.Completed, snapshot.phase)
            assertEquals("第一段", snapshot.text)
            assertTrue(usage(task).isEmpty())
        }
    }

    @Test fun existingDirectSseRequestsUseTheDurableTurnWithoutNeedingAModel(): Unit = runBlocking {
        val runtime = app.runtime()
        withPeer(response("data: {\"delta\":\"legacy\"}\n\n" + DONE)) { peer ->
            val task = runtime.chatStreams.start(ChatStreamRequest(peer.url("/legacy").toString(), "S1"))
            val sent = checkNotNull(peer.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/legacy", sent.path)
            assertEquals(JsonValue.text("S1"), JsonValue.parseObject(sent.body.readUtf8())["prompt"])
            val snapshot = withTimeout(10_000) { runtime.chatStreams.observe(task).first { it.phase != ChatStreamPhase.Running } }
            assertEquals(ChatStreamPhase.Completed, snapshot.phase)
            assertEquals("legacy", assistant(task).details.content)
            assertNotNull(checkNotNull(runtime.controller.snapshot(task)).execution.businessFactId)
        }
    }

    @Test fun cancelledRequestKeepsPartialReply(): Unit = runBlocking {
        val runtime = app.runtime()
        OpenSseTurnPeer(FIRST).use { peer ->
            val task = start(runtime, peer.url)
            waitForReply(task)
            runtime.chatStreams.cancel(task)
            val settled = withTimeout(10_000) { runtime.chatStreams.observe(task).first { it.phase != ChatStreamPhase.Running } }
            assertEquals(ChatStreamPhase.Cancelled, settled.phase)
            assertTrue(peer.awaitDisconnect())
            assertEquals("第一段", assistant(task).details.content)
            assertNotEquals("success", assistant(task).status)
            assertTrue(usage(task).isEmpty())
        }
    }

    @Test fun liveTurnIsKilledAfterDurablePartialReply(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("s303Phase") == "kill")
        val runtime = app.runtime()
        OpenSseTurnPeer(FIRST).use { peer ->
            val task = start(runtime, peer.url)
            waitForReply(task)
            assertEquals(RuntimeState.RUNNING, runtime.controller.snapshot(task)?.state)
            restartMarker.writeText("${Process.myPid()}\n${task.value}\n")
            delay(45_000)
            error("host must force-stop the process at the restart marker")
        }
    }

    @Test fun freshProcessReopensInterruptedReply(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("s303Phase") == "reopen")
        val (writer, taskRef) = restartMarker.readLines()
        assertNotEquals(writer.toInt(), Process.myPid())
        val task = TaskId.parse(taskRef)
        val runtime = app.runtime()
        val snapshot = checkNotNull(runtime.controller.snapshot(task))
        assertEquals(RuntimeState.INTERRUPTED, snapshot.state)
        assertNull(snapshot.execution.businessFactId)
        val observed = withTimeout(10_000) { runtime.chatStreams.observe(task).first() }
        assertEquals(ChatStreamPhase.Interrupted, observed.phase)
        assertEquals("第一段", observed.text)
        val block = assistant(task)
        assertEquals("第一段", block.details.content)
        assertEquals("error", block.status)
        assertTrue(restartMarker.delete())
    }

    private suspend fun start(runtime: MainRuntimeResources, peer: MockWebServer): TaskId =
        start(runtime, peer.url("/v1/chat/completions").toString())

    private suspend fun start(runtime: MainRuntimeResources, endpoint: String): TaskId =
        runtime.chatStreams.start(ChatStreamRequest(endpoint, "本机请求", model = "fixture-chat"))

    private suspend fun waitForReply(task: TaskId) = withTimeout(10_000) {
        while (app.storage().chatRepository.load(session(task))?.blocks?.none { it.details.content == "第一段" } != false) {
            delay(25)
        }
    }

    private suspend fun assistant(task: TaskId) = checkNotNull(app.storage().chatRepository.load(session(task))).let { chat ->
        val id = chat.messages.single { it.role == "assistant" }.blockIds.single()
        chat.blocks.single { it.id == id }
    }

    private suspend fun usage(task: TaskId): List<List<Long>> = app.storage().chatUsageDatabase().query(
        "SELECT prompt_tokens,completion_tokens,total_tokens,cached_tokens FROM llm_usage_logs WHERE session_id=?",
        listOf(SqlValue.Text(session(task))),
    ) { row -> List(4) { row.long(it) } }

    private fun session(task: TaskId) = "sess_${task.value.removePrefix("task_")}"

    private suspend fun withPeer(fixture: MockResponse, block: suspend (MockWebServer) -> Unit) {
        MockWebServer().use { peer ->
            peer.start(InetAddress.getByName("127.0.0.1"), 0)
            peer.enqueue(fixture)
            block(peer)
        }
    }

    private fun response(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)

    companion object {
        private const val FIRST = "data: {\"choices\":[{\"delta\":{\"content\":\"第一段\"}}]}\n\n"
        private const val SECOND = "data: {\"choices\":[{\"delta\":{\"content\":\"👩‍💻\"}}]}\n\n"
        private const val USAGE = "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":4," +
            "\"total_tokens\":15,\"prompt_tokens_details\":{\"cached_tokens\":3}}}\n\n"
        private const val PARTIAL_USAGE = "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":11}}\n\n"
        private const val DONE = "data: [DONE]\n\n"
    }
}

/** Flushes one complete SSE frame and keeps the response open until the client disconnects. */
private class OpenSseTurnPeer(frame: String) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val active = AtomicReference<Socket?>()
    private val disconnected = CountDownLatch(1)
    val url = "http://127.0.0.1:${server.localPort}/v1/chat/completions"

    private val worker = thread(name = "s303-open-sse", isDaemon = true) {
        try {
            server.accept().use { socket ->
                active.set(socket)
                val input = socket.getInputStream().buffered()
                val headers = readHeaders(input)
                val length = headers.lineSequence().first { it.startsWith("Content-Length:", ignoreCase = true) }
                    .substringAfter(':').trim().toInt()
                repeat(length) { check(input.read() != -1) }
                val content = frame.encodeToByteArray()
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n".encodeToByteArray())
                output.write("${content.size.toString(16)}\r\n".encodeToByteArray())
                output.write(content)
                output.write("\r\n".encodeToByteArray())
                output.flush()
                while (input.read() != -1) { }
                disconnected.countDown()
            }
        } catch (_: java.io.IOException) {
            if (!server.isClosed) disconnected.countDown()
        }
    }

    fun awaitDisconnect(): Boolean = disconnected.await(5, TimeUnit.SECONDS)

    override fun close() {
        server.close()
        active.get()?.close()
        worker.join(3_000)
        check(!worker.isAlive)
    }

    private fun readHeaders(input: java.io.InputStream): String {
        val buffer = StringBuilder()
        while (!buffer.endsWith("\r\n\r\n")) {
            val next = input.read()
            check(next >= 0 && buffer.length < 16_384) { "invalid fixture request" }
            buffer.append(next.toChar())
        }
        return buffer.toString()
    }
}
