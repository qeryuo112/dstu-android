package com.deepstudent.testing.exitprobe;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.List;
import java.util.TreeSet;

/** Process-local marker and strictly self-scoped historical exit query. */
final class ProcessExitProbe {
    static final String TAG = "DstuExitProbe";
    private static final long REPORT_TIMEOUT_MS = 3_000;
    private static final long REPORT_INTERVAL_MS = 100;
    private static Mark attempt;

    private ProcessExitProbe() {}

    static final class Mark {
        final boolean armed;
        final String token;

        Mark(boolean armed, String token) {
            this.armed = armed;
            this.token = token;
        }
    }

    /**
     * Exactly one attempt/marker per process, even when both listener and case call this.
     * A successful API call is not a promise that an exit record will contain its summary.
     */
    static synchronized Mark arm() {
        if (attempt != null) return attempt;
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Bundle args = InstrumentationRegistry.getArguments();
        String supplied = args.getString("dstuExitToken");
        String token = supplied == null ? ExitProbeProtocol.newToken() : supplied;
        int pid = Process.myPid();
        Bundle status = baseStatus(pid, ExitProbeProtocol.isToken(token) ? token : null);
        status.putLong("dstuExitTimestamp", System.currentTimeMillis());

        String state;
        String detail = null;
        boolean armed = false;
        if (!ExitProbeProtocol.isToken(token)) {
            state = "missing";
            detail = "invalid_token";
        } else if (Build.VERSION.SDK_INT < 30) {
            state = "unsupported";
            detail = "api_below_30";
        } else {
            try {
                Context context = selfTestContext(instrumentation);
                if (context == null) {
                    state = "unsupported";
                    detail = "not_self_test_package";
                } else {
                    status.putString("dstuExitInstrumentation", "self_target");
                    ActivityManager manager = activityManager(context);
                    if (manager == null) {
                        state = "unsupported";
                        detail = "activity_manager_unavailable";
                    } else {
                        Api30.setSummary(manager, token);
                        state = "armed";
                        armed = true;
                    }
                }
            } catch (RuntimeException | LinkageError ignored) {
                state = "unsupported";
                detail = "summary_set_failed";
            }
        }
        status.putString("dstuExitState", state);
        if (detail != null) status.putString("dstuExitDetail", detail);
        // If status delivery fails, control cases must not proceed to hold/kill.
        attempt = new Mark(armed, token);
        if (!emit(instrumentation, status)) attempt = new Mark(false, token);
        return attempt;
    }

    static void report(String pidArgument, String token, String cutoffArgument) {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        int pid = ExitProbeProtocol.parsePid(pidArgument);
        long cutoff = ExitProbeProtocol.parseEpochMs(cutoffArgument);
        Bundle status = baseStatus(pid, ExitProbeProtocol.isToken(token) ? token : null);
        if (pid <= 0 || cutoff <= 0 || !ExitProbeProtocol.isToken(token)) {
            finish(instrumentation, status, "missing", "invalid_arguments");
            return;
        }
        if (Build.VERSION.SDK_INT < 30) {
            finish(instrumentation, status, "unsupported", "api_below_30");
            return;
        }
        try {
            Context context = selfTestContext(instrumentation);
            if (context == null) {
                finish(instrumentation, status, "unsupported", "not_self_test_package");
                return;
            }
            status.putString("dstuExitInstrumentation", "self_target");
            ActivityManager manager = activityManager(context);
            if (manager == null) {
                finish(instrumentation, status, "unsupported", "activity_manager_unavailable");
                return;
            }
            status.putLong("dstuExitCutoffMs", cutoff);
            Api30.poll(instrumentation, manager, context.getPackageName(), pid, token, cutoff, status);
        } catch (SecurityException ignored) {
            finish(instrumentation, status, "unsupported", "historical_access_denied");
        } catch (RuntimeException | LinkageError ignored) {
            finish(instrumentation, status, "unsupported", "historical_query_failed");
        }
    }

    /** Refuse non-self instrumentation and non-test packages instead of querying another app. */
    private static Context selfTestContext(Instrumentation instrumentation) {
        Context test = instrumentation.getContext();
        Context target = instrumentation.getTargetContext();
        if (test == null || target == null) return null;
        String testPackage = test.getPackageName();
        if (testPackage == null || !testPackage.endsWith(".test")
                || !testPackage.equals(target.getPackageName())
                || test.getApplicationInfo().uid != Process.myUid()
                || target.getApplicationInfo().uid != Process.myUid()) {
            return null;
        }
        return target;
    }

    private static ActivityManager activityManager(Context context) {
        Object service = context.getSystemService(Context.ACTIVITY_SERVICE);
        return service instanceof ActivityManager ? (ActivityManager) service : null;
    }

    private static Bundle baseStatus(int pid, String token) {
        Bundle status = new Bundle();
        if (pid > 0) status.putInt("dstuExitPid", pid);
        if (token != null) status.putString("dstuExitToken", token);
        return status;
    }

    private static void finish(Instrumentation instrumentation, Bundle status,
            String state, String detail) {
        // A framework getter can throw partway through assembling a matching record.
        // Never expose a partial/stale reason on unsupported or missing outcomes.
        if (!"matched".equals(state)) {
            status.remove("dstuExitReason");
            status.remove("dstuExitReasonName");
            status.remove("dstuExitStatus");
            status.remove("dstuExitTimestamp");
            status.remove("dstuExitImportance");
            status.remove("dstuExitPssKb");
            status.remove("dstuExitRssKb");
            status.remove("dstuExitDescription");
        }
        status.putString("dstuExitState", state);
        if (detail != null) status.putString("dstuExitDetail", detail);
        emit(instrumentation, status);
    }

    /** Log first so a host acting immediately on the status cannot race the log. */
    private static boolean emit(Instrumentation instrumentation, Bundle status) {
        StringBuilder message = new StringBuilder();
        // Only protocol-controlled primitive values or fixed allowlisted clues enter this bundle.
        for (String key : new TreeSet<>(status.keySet())) {
            if (message.length() > 0) message.append(' ');
            message.append(key).append('=').append(status.get(key));
        }
        Log.i(TAG, message.toString());
        try {
            instrumentation.sendStatus(2, status);
            return true;
        } catch (RuntimeException | LinkageError ignored) {
            Log.i(TAG, "dstuExitState=unsupported dstuExitDetail=status_delivery_failed");
            return false;
        }
    }

    /** API-30 references are isolated so loading the listener on API 26-29 remains safe. */
    private static final class Api30 {
        static void setSummary(ActivityManager manager, String token) {
            manager.setProcessStateSummary(ExitProbeProtocol.summary(token));
        }

        static void poll(Instrumentation instrumentation, ActivityManager manager,
                String packageName, int pid, String token, long cutoff, Bundle status) {
            long deadline = SystemClock.elapsedRealtime() + REPORT_TIMEOUT_MS;
            boolean sawWithoutSummary = false;
            boolean sawDifferentSummary = false;
            boolean sawAfterCutoff = false;
            while (true) {
                List<ApplicationExitInfo> exits =
                        manager.getHistoricalProcessExitReasons(packageName, pid, 0);
                if (exits != null) {
                    for (ApplicationExitInfo exit : exits) {
                        // Recheck the PID even though the framework was asked to filter it.
                        if (exit == null || exit.getPid() != pid) continue;
                        byte[] summary = exit.getProcessStateSummary();
                        if (summary == null || summary.length == 0) {
                            sawWithoutSummary = true;
                        } else if (!ExitProbeProtocol.matchesSummary(summary, token)) {
                            sawDifferentSummary = true;
                        } else if (exit.getTimestamp() > cutoff) {
                            // A report invocation can force-stop a still-live instrumented process.
                            // Never attribute a matching record newer than the pre-report cutoff.
                            sawAfterCutoff = true;
                        } else {
                            matched(instrumentation, status, exit);
                            return;
                        }
                    }
                }
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) break;
                try {
                    Thread.sleep(Math.min(REPORT_INTERVAL_MS, remaining));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    finish(instrumentation, status, "missing", "report_interrupted");
                    return;
                }
            }
            String detail = sawAfterCutoff ? "exit_after_cutoff" : sawWithoutSummary && sawDifferentSummary
                    ? "summary_unavailable_and_token_mismatch"
                    : sawWithoutSummary ? "summary_unavailable"
                    : sawDifferentSummary ? "token_mismatch" : "no_pid_record";
            // In self-target instrumentation the record/summary may never be published. Neither
            // this detail nor a PID-only entry is sufficient to attribute a reason to this run.
            finish(instrumentation, status, "missing", detail);
        }

        private static void matched(Instrumentation instrumentation, Bundle status,
                ApplicationExitInfo exit) {
            int reason = exit.getReason();
            status.putInt("dstuExitReason", reason);
            status.putString("dstuExitReasonName", ExitProbeProtocol.reasonName(reason));
            status.putInt("dstuExitStatus", exit.getStatus());
            status.putLong("dstuExitTimestamp", exit.getTimestamp());
            status.putInt("dstuExitImportance", exit.getImportance());
            status.putLong("dstuExitPssKb", exit.getPss());
            status.putLong("dstuExitRssKb", exit.getRss());
            String clues = ExitProbeProtocol.systemClues(exit.getDescription());
            if (!clues.isEmpty()) status.putString("dstuExitDescription", clues);
            // Deliberately never access getTraceInputStream() or emit the raw description.
            finish(instrumentation, status, "matched", null);
        }
    }
}
