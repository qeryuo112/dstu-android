package com.deepstudent.testing.exitprobe;

import android.os.Bundle;
import android.os.Process;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Host-controlled diagnostic cases. Ordinary discovery cannot cause a kill or a wait. */
@RunWith(AndroidJUnit4.class)
public final class ProcessExitProbeAndroidTest {
    private static final long MAX_HOLD_MS = 30_000;

    @Test
    public void hold() throws InterruptedException {
        requireControl("hold");
        Thread.sleep(MAX_HOLD_MS);
    }

    @Test
    public void selfKill() {
        requireControl("selfKill");
        Process.killProcess(Process.myPid()); // SIGKILL only after both explicit host opt-ins.
        throw new AssertionError("current process survived SIGKILL");
    }

    @Test
    public void report() {
        Bundle args = InstrumentationRegistry.getArguments();
        String pid = args.getString("dstuExitPid");
        String token = args.getString("dstuExitToken");
        String cutoff = args.getString("dstuExitCutoffMs");
        // Normal unfiltered discovery has neither argument and should simply skip this test.
        Assume.assumeTrue("host must explicitly request the exit report", pid != null || token != null || cutoff != null);
        ProcessExitProbe.report(pid, token, cutoff);
    }

    private static void requireControl(String action) {
        Bundle args = InstrumentationRegistry.getArguments();
        String token = args.getString("dstuExitToken");
        Assume.assumeTrue("host must explicitly authorize this control case",
                action.equals(args.getString("dstuExitProbeControl"))
                        && ExitProbeProtocol.isToken(token));
        ProcessExitProbe.Mark mark = ProcessExitProbe.arm();
        // The listener and the case share this one attempt/status; never kill if arming failed.
        Assume.assumeTrue("process-exit summary could not be armed for this token",
                mark.armed && token.equals(mark.token));
    }
}
