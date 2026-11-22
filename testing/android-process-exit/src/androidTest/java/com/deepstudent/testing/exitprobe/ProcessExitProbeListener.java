package com.deepstudent.testing.exitprobe;

import android.util.Log;
import org.junit.runner.Description;
import org.junit.runner.notification.RunListener;

/** Optional AndroidJUnitRunner listener; it is never registered by default. */
public final class ProcessExitProbeListener extends RunListener {
    @Override
    public void testRunStarted(Description description) {
        try {
            ProcessExitProbe.arm();
        } catch (RuntimeException | LinkageError ignored) {
            // Diagnostics must not change the outcome of the actual test run.
            Log.i(ProcessExitProbe.TAG, "dstuExitState=unsupported dstuExitDetail=listener_failed");
        }
    }
}
