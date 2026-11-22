package com.deepstudent.app.benchmark

import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

/** Explicit opt-in only: these tests use the real app database, pages, PTY and configured provider. */
class BenchmarkPageDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun seedDisposableCorpus() {
        BenchmarkCorpus.requireConsent()
        BenchmarkCorpus.seed()
    }

    @Test fun verifyDisposableCorpus() {
        BenchmarkCorpus.requireConsent()
        BenchmarkCorpus.verify()
    }

    @Test(timeout = 360_000) fun notesScrollAndMemory() {
        BenchmarkCorpus.requireConsent()
        BenchmarkCorpus.verify()
        BenchmarkCorpus.withMainActivity { activity ->
            awaitTag("native-workspace")
            if (!hasTag("notes-list")) openPanel("notes")
            awaitTag("notes-list")
            compose.waitUntil(20_000) {
                compose.onAllNodesWithText("S1.27 corpus ", substring = true).fetchSemanticsNodes(false).isNotEmpty()
            }
            BenchmarkFrameSampler(activity).use { frames ->
                repeat(33) { index ->
                    val memory = BenchmarkPssSampler()
                    frames.begin()
                    try {
                        repeat(2) {
                            compose.onNodeWithTag("notes-list").performTouchInput { swipeUp() }
                            compose.onNodeWithTag("notes-list").performTouchInput { swipeDown() }
                        }
                        compose.waitForIdle()
                        Thread.sleep(150)
                    } finally { memory.close() }
                    val (durations, dropped) = frames.finish()
                    val points = memory.snapshot()
                    check(durations.isNotEmpty() && points.size >= 2) { "No usable frame or memory samples" }
                    val phase = if (index < 3) "warmup" else "measurement"
                    val number = if (index < 3) index + 1 else index - 2
                    BenchmarkCorpus.emit(JSONObject().put("type", "notes-trial").put("phase", phase)
                        .put("index", number).put("framesMs", JSONArray(durations)).put("droppedReports", dropped)
                        .put("pssSamples", JSONArray(points.map { JSONObject().put("elapsedMs", it.elapsedMs).put("pssMiB", it.pssMiB) })))
                }
            }
        }
    }

    @Test(timeout = 360_000) fun terminalRoundtrip() {
        BenchmarkCorpus.requireConsent()
        BenchmarkCorpus.verify()
        BenchmarkCorpus.withMainActivity {
            awaitTag("native-workspace")
            openPanel("terminal")
            awaitTag("terminal-screen")
            compose.onNodeWithContentDescription("新建会话").performClick()
            awaitTag("terminal-input")
            compose.waitUntil(15_000) { compose.onAllNodesWithText("运行中").fetchSemanticsNodes(false).isNotEmpty() }
            repeat(33) { index ->
                val marker = "BENCH_DONE_${index.toString().padStart(3, '0')}"
                // The literal marker is absent from the echoed shell command.
                val command = "i=0; while [ \$i -lt 128 ]; do printf '0123456789abcdef\\n' || exit 1; " +
                    "i=\$((i+1)); done; printf '\\102\\105\\116\\103\\110\\137\\104\\117\\116\\105\\137%03d\\n' $index"
                compose.onNodeWithTag("terminal-input").performTextInput(command)
                val start = SystemClock.elapsedRealtimeNanos()
                compose.onNodeWithContentDescription("发送").performClick()
                compose.waitUntil(20_000) {
                    compose.onAllNodesWithContentDescription(marker, substring = true)
                        .fetchSemanticsNodes(false).isNotEmpty()
                }
                val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                BenchmarkCorpus.emit(JSONObject().put("type", "terminal-trial")
                    .put("phase", if (index < 3) "warmup" else "measurement")
                    .put("index", if (index < 3) index + 1 else index - 2)
                    .put("durationMs", elapsed).put("outputBytes", 2176).put("markerObserved", true))
            }
        }
    }

    private fun openPanel(name: String) {
        val tag = "workspace-open-$name"
        awaitTag(tag)
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun hasTag(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes(false).isNotEmpty()
    private fun awaitTag(tag: String) { compose.waitUntil(30_000) { hasTag(tag) } }
}
