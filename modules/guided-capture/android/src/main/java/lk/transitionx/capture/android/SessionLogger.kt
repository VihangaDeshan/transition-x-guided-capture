// SPDX-License-Identifier: Apache-2.0
// Buffered, batched JSONL logging on a background writer thread (TDD v1.0 sections 13 and 14).
// Callers hand over plain data; JSON is built and written on the writer thread.
package lk.transitionx.capture.android

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import lk.transitionx.capture.core.CapturePose
import lk.transitionx.capture.core.SignalFrame
import lk.transitionx.capture.core.TrackingFailure
import lk.transitionx.capture.core.TrackingStatus
import lk.transitionx.capture.guidance.AlertEvent
import lk.transitionx.capture.replay.JsonLite
import lk.transitionx.capture.replay.LogCodec

class SessionLogger(dir: File) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "gc-log-writer").apply { priority = Thread.MIN_PRIORITY } }
    private val poses = writer(File(dir, "poses.jsonl"))
    private val quality = writer(File(dir, "quality.jsonl"))
    private val alerts = writer(File(dir, "alerts.jsonl"))
    @Volatile private var closed = false

    val poseCount = AtomicLong()
    val signalCount = AtomicLong()

    private fun writer(f: File) = BufferedWriter(OutputStreamWriter(FileOutputStream(f), Charsets.UTF_8), 64 * 1024)

    private fun submit(block: () -> Unit) {
        if (closed) return
        try {
            executor.execute {
                try { block() } catch (e: Exception) { Log.w(TAG, "log write failed", e) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "logger rejected task", e)
        }
    }

    fun pose(tNs: Long, pose: CapturePose, status: TrackingStatus, failure: TrackingFailure) {
        poseCount.incrementAndGet()
        submit { poses.write(LogCodec.poseLine(tNs, pose, status, failure)); poses.newLine() }
    }

    fun signal(s: SignalFrame) {
        signalCount.incrementAndGet()
        submit { quality.write(LogCodec.signalLine(s)); quality.newLine() }
    }

    fun alerts(events: List<AlertEvent>) {
        if (events.isEmpty()) return
        submit { for (e in events) { alerts.write(LogCodec.alertLine(e)); alerts.newLine() } }
    }

    /** Free-form session events (interruptions, recording problems) in the alert stream. */
    fun note(fields: Map<String, Any?>) {
        submit { alerts.write(JsonLite.write(fields)); alerts.newLine() }
    }

    /** Flushes and closes every stream; blocks until written (bounded). */
    fun close() {
        if (closed) return
        try {
            executor.submit(Runnable {
                for (w in listOf(poses, quality, alerts)) {
                    try { w.flush(); w.close() } catch (e: Exception) { Log.w(TAG, "close failed", e) }
                }
            }).get(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "logger close timed out", e)
        }
        closed = true
        executor.shutdown()
    }

    companion object { private const val TAG = "GuidedCaptureLog" }
}
