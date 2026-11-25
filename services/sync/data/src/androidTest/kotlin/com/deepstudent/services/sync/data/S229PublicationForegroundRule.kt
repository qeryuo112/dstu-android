package com.deepstudent.services.sync.data

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** The instrumentation runner clears Activities after every case, so each case gets a fresh host. */
class S229PublicationForegroundRule : ExternalResource() {
    private var host: ActivityScenario<S229PublicationHostActivity>? = null

    override fun before() {
        if (InstrumentationRegistry.getArguments().getString("s229_foreground") != "true") return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, S229PublicationHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = ActivityScenario.launch<S229PublicationHostActivity>(intent)
        try {
            check(activity.state == Lifecycle.State.RESUMED) { "S2.29 test host must be foreground" }
            host = activity
        } catch (failure: Exception) {
            activity.close()
            throw failure
        }
    }

    override fun after() {
        host?.close()
        host = null
    }
}

/** Exists only in the standalone test APK and has no exported entry point. */
class S229PublicationHostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply { text = "DSTU S2.29 · isolated sync test" })
    }
}
