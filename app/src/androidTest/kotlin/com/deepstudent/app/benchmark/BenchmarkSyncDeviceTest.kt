package com.deepstudent.app.benchmark

import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.DstuApplication
import com.deepstudent.app.sync.publicEncryptionPassword
import com.deepstudent.core.common.id.Digests
import com.deepstudent.features.notes.api.CreateNote
import com.deepstudent.features.sync.ui.SyncUiState
import com.deepstudent.services.sync.api.PairingMode
import com.deepstudent.services.sync.api.PairingRequest
import com.deepstudent.services.sync.api.SyncTransferPhase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

/** One isolated encrypted roundtrip; only the real page's sync action is timed. */
class BenchmarkSyncDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test(timeout = 120_000) fun syncOnce() {
        BenchmarkCorpus.requireConsent()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(instrumentation.targetContext.packageName == "com.deepstudent.app.benchmark")
        val endpoint = requireNotNull(arguments.getString("syncEndpoint"))
        require(Regex("http://127\\.0\\.0\\.1:[1-9][0-9]{0,4}/dav/").matches(endpoint))
        val inboundNote = fixtureId(arguments.getString("syncNote"))
        val inboundFile = fixtureId(arguments.getString("syncFile"))
        val app = DstuApplication.from(instrumentation.targetContext)
        val storage = runBlocking { app.storage() }
        val controller = runBlocking { app.sync() }
        val outbound = runBlocking {
            check(storage.notes.list(includeDeleted = true).isEmpty())
            val before = withTimeout(10_000) { controller.state.first { it != SyncUiState.Loading } }
            check(before == SyncUiState.Unconfigured)
            val note = storage.notes.create(CreateNote("Public S1.27 Android", OUTBOUND, listOf("public")))
            controller.pair(PairingMode.JOIN, PairingRequest(endpoint, "public-sync-user", "s1-26",
                "public-fixture-password".toCharArray(), publicEncryptionPassword()))
            val paired = withTimeout(20_000) { controller.state.first { it is SyncUiState.Connected || it is SyncUiState.Error } }
            check(paired is SyncUiState.Connected && paired.configuration.encrypted) { "Isolated pairing failed" }
            note.id
        }
        val elapsed = BenchmarkCorpus.withMainActivity {
            awaitTag("workspace-open-sync")
            compose.onNodeWithTag("workspace-open-sync").performScrollTo().performClick()
            awaitTag("sync-transfer-start")
            check(!controller.running.value && controller.transfer.value.phase == SyncTransferPhase.IDLE)
            val start = SystemClock.elapsedRealtimeNanos()
            compose.onNodeWithTag("sync-transfer-start").performClick()
            compose.waitUntil(90_000) {
                !controller.running.value && controller.transfer.value.phase in setOf(SyncTransferPhase.COMPLETE, SyncTransferPhase.FAILED)
            }
            val duration = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            check(controller.transfer.value.phase == SyncTransferPhase.COMPLETE) { "Encrypted sync did not finish" }
            duration
        }
        runBlocking {
            check(storage.notes.get(inboundNote)?.content == "Public desktop note: 中文 😀\n")
            val bytes = requireNotNull(storage.attachments.get(inboundFile)).bytes
            check(bytes.size == 1_048_593 && Digests.sha256Hex(bytes) == INBOUND_SHA256)
            check(storage.notes.attachmentLinks(inboundNote).any { it.targetAvailable })
        }
        BenchmarkCorpus.emit(JSONObject().put("type", "sync-trial").put("durationMs", elapsed)
            .put("androidVerified", true).put("androidNoteId", outbound))
    }

    private fun awaitTag(tag: String) = compose.waitUntil(30_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun fixtureId(value: String?) = requireNotNull(value).also { require(Regex("[A-Za-z0-9_-]{1,180}").matches(it)) }

    private companion object {
        const val OUTBOUND = "Public Android benchmark note: 中文 😀\n"
        const val INBOUND_SHA256 = "c26032d5154f96bd29c799447d715ab681d8d0aa308ecc6f321a35d98f0672da"
    }
}
