package com.deepstudent.app.storage

import android.content.ContextWrapper
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.core.common.id.Digests
import com.deepstudent.platform.database.SqlDatabase
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DesktopVfsUpgradeDeviceTest {
    @Test fun desktopVfsOpensAndUpgradesThroughAppStorage() {
        val runner = InstrumentationRegistry.getInstrumentation()
        assumeTrue("requires a host-generated desktop file", InstrumentationRegistry.getArguments().getString("class") ==
            "${javaClass.name}#desktopVfsOpensAndUpgradesThroughAppStorage")
        val base = runner.targetContext
        val root = File(base.cacheDir, "s2-desktop-vfs")
        val context = object : ContextWrapper(base) { override fun getFilesDir() = root }
        assertTrue("stage the desktop fixture before running", root.resolve("active/databases/vfs.db").isFile)
        try {
            val verifiedAt = MainStorageResources.open(context).use { storage ->
                val db = storage.databaseForSync()
                val report = checkNotNull(db.migrationReport)
                assertEquals(20261111, report.fromVersion)
                assertEquals(20261120, report.toVersion)
                assertEquals(4, report.appliedCount)
                assertEquals(FINGERPRINT, report.fingerprint)
                assertEquals(listOf("ok"), db.query("PRAGMA integrity_check") { it.text(0) })
                assertTrue(db.query("PRAGMA foreign_key_check") { it.long(0) }.isEmpty())
                assertOldRowAndHistory(db)
                governance(db)
            }
            MainStorageResources.open(context).use { storage ->
                val db = storage.databaseForSync()
                assertEquals(0, db.migrationReport?.appliedCount)
                assertEquals("verified_at must not be rewritten on restart", verifiedAt, governance(db))
                assertOldRowAndHistory(db)
            }
            Log.i("DstuDesktopUpgrade", "database=vfs from=20261111 to=20261120 applied=4 restartApplied=0 " +
                "fingerprint=$FINGERPRINT historySha256=$HISTORY_SHA256 preservedColumns=18 fsrsDefaults=7")
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertOldRowAndHistory(db: SqlDatabase) {
        val row = db.query("""
            SELECT json_array(id,question_id,exam_id,ease_factor,interval_days,repetitions,next_review_date,last_review_date,
                status,total_reviews,total_correct,consecutive_failures,is_difficult,created_at,updated_at,device_id,
                local_version,deleted_at), json_array(fsrs_stability,fsrs_difficulty,fsrs_phase,fsrs_learning_step,fsrs_lapses,
                fsrs_due_at,fsrs_last_review_at) FROM review_plans WHERE id='rp_due_fixture'
            """.trimIndent()) { it.text(0) to it.text(1) }.single()
        assertEquals(OLD_ROW, row.first)
        assertEquals("[null,null,null,0,0,null,null]", row.second)
        val history = db.query("""
            SELECT json_object('version',version,'name',name,'checksum',checksum) FROM refinery_schema_history ORDER BY version
            """.trimIndent()) { it.text(0) }
        assertEquals(76, history.size)
        assertEquals(HISTORY_SHA256, Digests.sha256Hex(history.joinToString(",", "[", "]").toByteArray()))
    }

    private fun governance(db: SqlDatabase): String = db.query("""
        SELECT fingerprint,canonical_schema,verified_at FROM __governance_schema_fingerprints
        WHERE database_id='vfs' AND schema_version=20261120
        """.trimIndent()) { row ->
        assertEquals(FINGERPRINT, row.text(0))
        assertEquals(FINGERPRINT, Digests.sha256Hex(row.text(1).toByteArray()))
        row.text(2)
    }.single()

    private companion object {
        const val HISTORY_SHA256 = "65175d438cc6ada353d5a5190eba80438ae3bf7d424a16a88ad1c2d50ab17a94"
        const val FINGERPRINT = "7dec0d2ff3cf1c40cb15e73396c05cbcab925487661b3e09a69a6b1f30f8345b"
        const val OLD_ROW = "[\"rp_due_fixture\",\"q_due_fixture\",\"exam_fsrs_fixture\",2.3,6,3,\"2026-11-01\",\"2026-10-26\"," +
            "\"reviewing\",5,4,0,1,\"2026-10-20T08:00:00Z\",\"2026-10-20T08:00:00Z\",\"upgrade-device\",7,null]"
    }
}
