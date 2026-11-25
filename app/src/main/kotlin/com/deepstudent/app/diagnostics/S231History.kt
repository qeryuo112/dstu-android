package com.deepstudent.app.diagnostics

import com.deepstudent.core.common.logging.LogLevel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.Executors

/** Private, bounded projected entries only. Corrupt or unknown formats are never displayed. */
internal class S231History(private val directory: File) {
    private val target = File(directory, "s231-diagnostic-history.bin")

    fun read(): List<S231Entry> = runCatching {
        if (!target.isFile || target.length() > MAX_BYTES) return emptyList()
        val bytes = target.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(1024)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES)
                output.write(chunk, 0, count)
            }
            output.toByteArray()
        }
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC)
            val count = input.readInt()
            require(count in 0..S231Snapshot.MAX_RECORDS)
            val entries = List(count) { readEntry(input) }
            require(input.read() == -1)
            entries
        }
    }.getOrDefault(emptyList())

    fun write(entries: List<S231Entry>) {
        require(entries.size <= S231Snapshot.MAX_RECORDS)
        val bytes = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeInt(entries.size)
                entries.forEach { writeEntry(data, it) }
            }
            output.toByteArray()
        }
        require(bytes.size <= MAX_BYTES)
        check(directory.isDirectory || directory.mkdirs())
        val temporary = Files.createTempFile(directory.toPath(), ".s231-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use {
                it.write(bytes)
                it.fd.sync()
            }
            Files.move(temporary, target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun writeEntry(out: DataOutputStream, entry: S231Entry) {
        out.writeEnum(entry.level)
        out.writeEnum(entry.event)
        listOf(entry.library, entry.task, entry.correlation).forEach { out.writeInt(it ?: 0) }
        out.writeEnum(entry.location.owner)
        out.writeEnum(entry.location.database)
        out.writeEnum(entry.location.stage)
        out.writeEnum(entry.location.phase)
        out.writeEnum(entry.progress.status)
        out.writeEnum(entry.progress.state)
        out.writeEnum(entry.progress.outcome)
        out.writeEnum(entry.error)
        out.writeLong(entry.progress.durationMillis ?: -1)
    }

    private fun DataOutputStream.writeEnum(value: Enum<*>?) = writeUTF(value?.name.orEmpty())

    private fun readEntry(input: DataInputStream): S231Entry {
        val level = enumValue<LogLevel>(input.readUTF()) ?: error("missing level")
        val event = enumValue<S231Event>(input.readUTF())
        val library = alias(input.readInt())
        val task = alias(input.readInt())
        val correlation = alias(input.readInt())
        val location = S231Location(
            enumValue<S231Owner>(input.readUTF()),
            enumValue<S231Database>(input.readUTF()),
            enumValue<S231Stage>(input.readUTF()),
            enumValue<S231Stage>(input.readUTF()),
        )
        val status = enumValue<S231Status>(input.readUTF())
        val state = enumValue<S231State>(input.readUTF())
        val outcome = enumValue<S231Outcome>(input.readUTF())
        val error = enumValue<S231ErrorCode>(input.readUTF())
        val duration = input.readLong()
        require(duration in -1..86_400_000)
        return S231Entry(
            level,
            event,
            library,
            task,
            correlation,
            location,
            S231Progress(status, state, outcome, duration.takeIf { it >= 0 }),
            error,
        )
    }

    private fun alias(value: Int): Int? {
        require(value >= 0)
        return value.takeIf { it > 0 }
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String): T? =
        if (value.isEmpty()) null else enumValues<T>().first { it.name == value }

    companion object {
        const val MAX_BYTES = 32 * 1024
        private const val MAGIC = 0x53323331
    }
}

/** Coalesces snapshots to one pending bounded value; logging never waits for disk I/O. */
internal class S231HistoryWriter(private val history: S231History) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "diagnostic-history").apply { isDaemon = true }
    }
    private var pending: List<S231Entry>? = null
    private var running = false

    @Synchronized
    fun submit(snapshot: S231Snapshot) {
        pending = snapshot.entries
        if (running) return
        running = true
        executor.execute { drain() }
    }

    private fun drain() {
        while (true) {
            val next = synchronized(this) {
                val current = pending
                pending = null
                if (current == null) running = false
                current
            } ?: return
            runCatching { history.write(next) }
        }
    }
}
