package com.deepstudent.features.chat.data

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.chat.backend.application.ChatStreamHandler
import com.deepstudent.features.chat.backend.application.LoadTurnSnapshot
import com.deepstudent.platform.database.BundledMigrationSource
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.network.OkHttpNetworkTransport
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.RuntimeTaskContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class S301LocalStreamRestartTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.filesDir, "s301-loopback-restart")
    private val writerPid = File(root, "writer-pid")
    private val task = TaskId.parse("task_s301_loopback")
    private val sessionId = "sess_s301_loopback"

    @Test
    fun completedStreamPersistsAcrossReopen(): Unit = runBlocking {
        when (val phase = InstrumentationRegistry.getArguments().getString("s301Phase")) {
            "write" -> write()

            "read" -> {
                assertNotEquals(writerPid.readText().toInt(), Process.myPid())
                verifyReadAndClean()
            }

            "desktop" -> verifyDesktopAndUpdateTitle()

            null -> {
                write()
                verifyReadAndClean()
            }

            else -> error("unsupported S3.01 test phase: $phase")
        }
    }

    private suspend fun write() {
        root.deleteRecursively()
        check(root.mkdirs())
        openChat().use { database ->
            val repository = SqlChatRepository(database)
            val taskContext = CheckpointRecorder(task)
            MockWebServer().use { peer ->
                peer.start(InetAddress.getByName("127.0.0.1"), 0)
                peer.enqueue(
                    MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                        "data: {\"delta\":\"中文\"}\n\ndata: {\"delta\":\"👩‍💻\"}\n\ndata: [DONE]\n\n",
                    ),
                )
                OkHttpNetworkTransport().use { network ->
                    val handler = ChatStreamHandler(NetworkChatStream(network, allowLoopback = true), repository)
                    val payload = JsonValue.Object.Builder()
                        .put("endpoint", "http://127.0.0.1:${peer.port}/stream")
                        .put("prompt", "S3.01 本机样本")
                        .build()
                    withTimeout(10_000) { handler.execute(taskContext, payload) }
                }
                assertEquals("POST", checkNotNull(peer.takeRequest(5, TimeUnit.SECONDS)).method)
            }
            assertEquals(listOf("中文", "中文👩‍💻", "中文👩‍💻"), taskContext.text)
        }
        writerPid.writeText(Process.myPid().toString())
    }

    private suspend fun verifyReadAndClean() {
        try {
            openChat().use { database ->
                val chat = checkNotNull(SqlChatRepository(database).load(sessionId))
                assertEquals("general_chat", chat.session.mode)
                assertEquals(listOf("user", "assistant"), chat.messages.map { it.role })
                val blocks = chat.blocks.associateBy { it.id }
                val ordered = chat.messages.map { blocks.getValue(it.blockIds.single()) }
                assertEquals(chat.messages.map { it.id }, ordered.map { it.messageId })
                assertEquals(listOf("S3.01 本机样本", "中文👩‍💻"), ordered.map { it.details.content })
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private suspend fun verifyDesktopAndUpdateTitle() {
        check(File(root, "chat_v2.db").isFile) { "frozen desktop sample is missing" }
        openChat().use { database ->
            val repository = SqlChatRepository(database)
            val session = "sess_s301_desktop_oracle"
            val loadSnapshot = LoadTurnSnapshot(repository)
            val snapshot = checkNotNull(loadSnapshot(session))
            val fixture = snapshot.conversation
            assertEquals(snapshot, loadSnapshot(session))
            assertEquals("S3.01 desktop", fixture.session.details.title)
            assertEquals("2026-11-25T12:00:02.123456789+00:00", fixture.session.updatedAt)
            val metadata = fixture.session.details.metadata as JsonValue.Object
            assertEquals(JsonValue.Number.raw("9223372036854775807"), metadata["futureCounter"])
            val variant = fixture.messages.last().variantItems()?.single() ?: error("missing variant")
            assertEquals("var_s301_oracle", variant.id)
            assertEquals(JsonValue.Null, variant.value["futureVariant"])
            assertEquals("frozen desktop fixture 👩‍💻", fixture.blocks.last().details.content)
            assertEquals(JsonValue.Null, fixture.blocks.last().details.toolOutput)
            val edited = fixture.session.copy(details = fixture.session.details.copy(title = "S3.01 Android edit"))
            repository.save(fixture.copy(session = edited))
            assertEquals("S3.01 Android edit", loadSnapshot(session)?.conversation?.session?.details?.title)
            assertEquals("S3.01 desktop", snapshot.conversation.session.details.title)
        }
    }

    private fun openChat() = DatabaseFactory.openShared(
        File(root, "chat_v2.db"),
        SharedDatabase.CHAT_V2,
        BundledMigrationSource(context),
    )

    private class CheckpointRecorder(override val taskId: TaskId) : RuntimeTaskContext {
        override val generation = 1L
        val text = mutableListOf<String>()

        override suspend fun checkpoint(value: JsonValue?) {
            text += ((value as JsonValue.Object)["text"] as JsonValue.Text).value
        }

        override suspend fun transition(state: RuntimeState) = Unit
        override suspend fun renewLease() = Unit
    }
}
