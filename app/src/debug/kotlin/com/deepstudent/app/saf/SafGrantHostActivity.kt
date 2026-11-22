package com.deepstudent.app.saf

import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity

/** Only hosts system/fixture Activity results. Does not open the workspace or the user's store. */
class SafGrantHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // This debug-only surface is visible while the test is not in the picker.
        setContentView(TextView(this).apply { text = "S2.07 SAF instrumentation" })
    }
}
