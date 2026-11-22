package com.deepstudent.features.terminal.backend

import com.deepstudent.features.terminal.api.TerminalCell
import com.deepstudent.features.terminal.api.TerminalCommandException
import com.deepstudent.features.terminal.api.TerminalFailure
import com.deepstudent.features.terminal.api.TerminalPhase
import com.deepstudent.features.terminal.api.TerminalScreen
import com.deepstudent.features.terminal.api.TerminalSessionId
import com.deepstudent.features.terminal.api.TerminalStyle
import com.deepstudent.features.terminal.api.TerminalSize
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSessionServiceTest {
    @Test
    fun multipleSessionsKeepOutputAndExitCodesSeparate() = runBlocking {
        val first = FakeProcess("FIRST 中😀\n".toByteArray())
        val second = FakeProcess("SECOND\n".toByteArray())
        val queue = ArrayDeque(listOf(first, second))
        val service = service { queue.removeFirst() }
        try {
            val firstId = service.open()
            val secondId = service.open()
            assertEquals(listOf(1, 2), service.sessions.value.map { it.ordinal })
            awaitText(service, firstId, "FIRST 中😀")
            awaitText(service, secondId, "SECOND")
            first.exit(37)
            second.exit(9)
            awaitExit(service, firstId, 37)
            awaitExit(service, secondId, 9)
            assertFalse(text(service, firstId).contains("SECOND"))
            assertFalse(text(service, secondId).contains("FIRST"))
        } finally {
            service.shutdown()
        }
    }

    @Test fun duplicateIdNeverReplacesAnExistingOwner(): Unit = runBlocking {
        val first = FakeProcess()
        val second = FakeProcess()
        val queue = ArrayDeque(listOf(first, second))
        val service = TerminalSessionService(TerminalProcessFactory { queue.removeFirst() },
            TerminalScreenFactory(::FakeScreen), TerminalIdFactory { "duplicate" })
        try {
            val id = service.open()
            try { service.open(); throw AssertionError("duplicate ID accepted") }
            catch (expected: TerminalCommandException) { assertEquals(TerminalFailure.SESSION_CLOSED, expected.failure) }
            assertEquals(1, service.sessions.value.size)
            assertEquals(0, first.closes.get())
            assertEquals(1, second.closes.get())
            service.interrupt(id)
            assertEquals(1, first.interrupts.get())
        } finally { service.shutdown() }
    }

    @Test
    fun interruptSendsControlCharacterWithoutKillingSession() = runBlocking {
        val process = FakeProcess()
        val service = service { process }
        try {
            val id = service.open()
            service.interrupt(id)
            assertEquals(1, process.interrupts.get())
            assertEquals(0, process.terminations.get())
            assertEquals(TerminalPhase.RUNNING, service.observe(id)!!.value.phase)
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun closeIsIdempotentAndReleasesTheOwnerOnce() = runBlocking {
        val process = FakeProcess()
        val service = service { process }
        val id = service.open()
        service.close(id)
        service.close(id)
        assertEquals(1, process.closes.get())
        assertTrue(service.sessions.value.isEmpty())
    }

    @Test
    fun terminatePublishesExitCodeAndUnknownSessionFailsClosed() = runBlocking {
        val process = FakeProcess()
        val service = service { process }
        try {
            val id = service.open()
            service.terminate(id)
            awaitExit(service, id, 137)
            try {
                service.write(TerminalSessionId("missing"), "ignored")
                throw AssertionError("Expected closed-session failure")
            } catch (expected: TerminalCommandException) {
                assertEquals(TerminalFailure.SESSION_CLOSED, expected.failure)
            }
        } finally {
            service.shutdown()
        }
    }

    private fun service(factory: () -> FakeProcess): TerminalSessionService {
        var ordinal = 0
        return TerminalSessionService(
            processes = TerminalProcessFactory { factory() },
            screens = TerminalScreenFactory(::FakeScreen),
            ids = TerminalIdFactory { "session-${++ordinal}" },
        )
    }

    private suspend fun awaitText(
        service: TerminalSessionService,
        id: TerminalSessionId,
        value: String,
    ) = withTimeout(3_000) {
        while (!text(service, id).contains(value)) delay(10)
    }

    private suspend fun awaitExit(
        service: TerminalSessionService,
        id: TerminalSessionId,
        code: Int,
    ) = withTimeout(3_000) {
        while (service.observe(id)?.value?.let { it.phase != TerminalPhase.EXITED || it.exitCode != code } != false) {
            delay(10)
        }
    }

    private fun text(service: TerminalSessionService, id: TerminalSessionId): String {
        val screen = service.observe(id)!!.value.screen
        return screen.lines.joinToString("\n") { row -> row.joinToString("") { it.text }.trimEnd() }
    }

    private class FakeScreen(var size: TerminalSize) : TerminalScreenPort {
        private val content = StringBuilder()

        override fun append(text: String) {
            content.append(text)
        }

        override fun resize(size: TerminalSize) {
            this.size = size
        }

        override fun snapshot(): TerminalScreen {
            val style = TerminalStyle(-1, -16_777_216)
            val rows = if (content.isEmpty()) listOf("") else content.toString().split('\n')
            val lines = List(size.rows) { index ->
                val value = rows.getOrElse(index) { "" }
                List(size.columns) { column ->
                    TerminalCell(value.getOrElse(column) { ' ' }.toString(), style)
                }
            }
            return TerminalScreen(size, lines, 0, 0, 0, true)
        }
    }

    private class FakeProcess(private val bytes: ByteArray = ByteArray(0)) : TerminalProcessPort {
        private val closed = CountDownLatch(1)
        private val exit = CountDownLatch(1)
        private var exitCode = -1
        val interrupts = AtomicInteger()
        val terminations = AtomicInteger()
        val closes = AtomicInteger()

        override val input: InputStream = object : InputStream() {
            private var offset = 0

            override fun read(): Int = read(ByteArray(1), 0, 1).let { if (it < 0) -1 else 0 }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (this.offset >= bytes.size) {
                    closed.await(10, TimeUnit.MILLISECONDS)
                    return -1
                }
                val count = minOf(length, bytes.size - this.offset)
                bytes.copyInto(buffer, offset, this.offset, this.offset + count)
                this.offset += count
                return count
            }
        }

        override val output: OutputStream = ByteArrayOutputStream()

        override fun resize(rows: Int, columns: Int): Boolean = true

        override fun interrupt() {
            interrupts.incrementAndGet()
        }

        override fun terminate() {
            terminations.incrementAndGet()
            exitCode = 137
            exit.countDown()
        }

        override fun awaitExit(): Int {
            exit.await()
            return exitCode
        }

        override fun close() {
            closes.incrementAndGet()
            closed.countDown()
            exit.countDown()
        }

        fun exit(code: Int) {
            exitCode = code
            exit.countDown()
        }
    }
}
