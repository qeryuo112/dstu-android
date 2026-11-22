package com.deepstudent.app.benchmark

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.DstuApplication
import com.deepstudent.app.editor.EditorTestHost
import com.deepstudent.features.notes.api.CreateNote
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue

internal object BenchmarkCorpus {
    const val ID = "s1-27-notes-v1"
    const val SHA256 = "2e70cb23f302b8d415b1dbd8ceca1460fb1c68928280e0f5bd5ba0a02e61be45"
    const val COUNT = 64
    private val tags = listOf("s1-27-benchmark")

    fun title(index: Int) = "S1.27 corpus ${index.toString().padStart(3, '0')}"
    fun content(index: Int) = (0 until 12).joinToString("") { line ->
        "S1.27 固定语料 ${index.toString().padStart(3, '0')} / ${line.toString().padStart(2, '0')} 中文 😀\n"
    }

    fun requireConsent() {
        assumeTrue("S1.27 runs only with explicit disposable-data consent",
            InstrumentationRegistry.getArguments().getString("benchmarkConsent") == "disposable")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName == "com.deepstudent.app.benchmark" &&
            instrumentation.context.packageName == "com.deepstudent.app.benchmark.test") {
            "S1.27 refuses to run against a non-benchmark application or test package"
        }
    }

    fun emit(event: JSONObject) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("s127event", event.toString())
        })
    }

    fun <T> withMainActivity(block: (Activity) -> T): T {
        val host = EditorTestHost()
        try {
            host.start()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.runOnMainSync { host.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            return block(host.activity)
        } finally { host.close() }
    }

    fun seed() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val notes = DstuApplication.from(context).storage().notes
        check(notes.list(includeDeleted = true).isEmpty()) { "Refusing to seed a nonempty app database" }
        repeat(COUNT) { index -> notes.create(CreateNote(title(index), content(index), tags)) }
        verify()
    }

    fun verify() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val notes = DstuApplication.from(context).storage().notes.list(includeDeleted = true).sortedBy { it.title }
        check(notes.size == COUNT) { "Expected exactly $COUNT fixed notes, found ${notes.size}" }
        val digest = MessageDigest.getInstance("SHA-256")
        notes.forEachIndexed { index, note ->
            check(note.deletedAt == null && note.title == title(index) && note.content == content(index) && note.tags == tags) {
                "Corpus differs at index $index"
            }
            listOf(note.title, note.content, *note.tags.toTypedArray()).forEach { text ->
                val bytes = text.toByteArray(Charsets.UTF_8)
                digest.update("${bytes.size}:".toByteArray(Charsets.US_ASCII))
                digest.update(bytes)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(actual == SHA256) { "Corpus digest mismatch" }
        emit(JSONObject().put("type", "corpus").put("id", ID).put("sha256", actual).put("count", COUNT))
    }
}
