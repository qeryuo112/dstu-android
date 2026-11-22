package com.deepstudent.app.benchmark

import android.app.Activity
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Measures actual window frames and sampled PSS in the instrumented main process. */
internal class BenchmarkFrameSampler(private val activity: Activity) : AutoCloseable {
    private val thread = HandlerThread("s127-frame-metrics").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()
    private val frames = mutableListOf<Double>()
    private var dropped = 0
    private var active = false
    private val listener = Window.OnFrameMetricsAvailableListener { _, frame, droppedReports ->
        synchronized(lock) {
            if (active) {
                val nanos = frame.getMetric(FrameMetrics.TOTAL_DURATION)
                if (nanos > 0) frames.add(nanos / 1_000_000.0)
                dropped += droppedReports
            }
        }
    }

    init { InstrumentationRegistry.getInstrumentation().runOnMainSync {
        activity.window.addOnFrameMetricsAvailableListener(listener, handler)
    } }

    fun begin() {
        barrier()
        synchronized(lock) { frames.clear(); dropped = 0; active = true }
    }

    fun finish(): Pair<List<Double>, Int> {
        barrier()
        return synchronized(lock) { active = false; frames.toList() to dropped }
    }

    private fun barrier() {
        val latch = CountDownLatch(1)
        check(handler.post { latch.countDown() } && latch.await(5, TimeUnit.SECONDS)) { "Frame metrics handler stalled" }
    }

    override fun close() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            activity.window.removeOnFrameMetricsAvailableListener(listener)
        }
        thread.quitSafely()
        thread.join(5_000)
    }
}

internal data class PssPoint(val elapsedMs: Double, val pssMiB: Double)

internal class BenchmarkPssSampler : AutoCloseable {
    private val active = AtomicBoolean(true)
    private val points = mutableListOf<PssPoint>()
    private val started = SystemClock.elapsedRealtimeNanos()
    private val thread = Thread {
        while (active.get()) {
            val elapsed = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
            val pssMiB = Debug.getPss() / 1024.0
            synchronized(points) { points.add(PssPoint(elapsed, pssMiB)) }
            Thread.sleep(100)
        }
    }.apply { name = "s127-pss"; start() }

    override fun close() {
        active.set(false)
        thread.join(5_000)
        check(!thread.isAlive) { "PSS sampler did not stop" }
    }

    fun snapshot(): List<PssPoint> = synchronized(points) { points.toList() }
}
