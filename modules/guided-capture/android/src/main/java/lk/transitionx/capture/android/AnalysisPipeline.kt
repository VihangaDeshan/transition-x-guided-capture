// SPDX-License-Identifier: Apache-2.0
// Analysis thread: signal analysis + decision engine (TDD v1.0 section 13). Frames are offered
// through a single-slot mailbox: if a pass is still running when the next frame arrives, the
// older pending frame is dropped, never queued.
package lk.transitionx.capture.android

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import lk.transitionx.capture.core.CaptureFrame
import lk.transitionx.capture.core.GuidanceState
import lk.transitionx.capture.core.SignalFrame
import lk.transitionx.capture.guidance.EngineSummary
import lk.transitionx.capture.guidance.GuidanceEngine
import lk.transitionx.capture.guidance.Thresholds
import lk.transitionx.capture.replay.LogCodec
import lk.transitionx.capture.signals.MotionConfig
import lk.transitionx.capture.signals.SignalAnalyzer

class AnalysisPipeline(
    context: Context,
    private val thresholds: Thresholds,
    private val logger: SessionLogger,
    private val emit: (String, Map<String, Any?>) -> Unit,
    private val debugSignals: () -> Boolean,
    private val onMaxDuration: () -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "gc-analysis") }
    private val pending = AtomicReference<CaptureFrame?>(null)
    private val scheduled = AtomicBoolean(false)
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val analyzer = SignalAnalyzer(
        MotionConfig(
            spinWindowNs = thresholds.spinWindowMs * 1_000_000L,
            keyframeBaselineM = thresholds.baselineMinM
        )
    )
    private val engine = GuidanceEngine(thresholds)

    /** Set by the GL thread when it has written the shared luma buffer; cleared after analysis. */
    val lumaBusy = AtomicBoolean(false)
    private var lumaBuffer = ByteArray(0)

    @Volatile var sampleInterval: Int = thresholds.imageSampleInterval.coerceAtLeast(1)
        private set
    @Volatile var imageSuspended: Boolean = false
        private set
    @Volatile var thermalStatus: Int = 0
        private set

    // analysis-thread state
    private var engineStarted = false
    private var startMs = 0L
    private var lastStatusMs = 0L
    private var lastSignalEmitMs = 0L
    private var lastThermalCheckMs = 0L
    private var maxDurationFired = false
    private var lastSignal: SignalFrame? = null
    private var lastState: GuidanceState? = null
    private val trail = ArrayList<FloatArray>()
    private var trailEmitted = 0
    @Volatile private var imageSamples = 0L
    @Volatile private var finished = false

    val imageSampleCount: Long get() = imageSamples

    /** Called on the GL thread while it holds [lumaBusy]; returns a buffer of at least [size]. */
    fun lumaBufferFor(size: Int): ByteArray {
        if (lumaBuffer.size < size) lumaBuffer = ByteArray(size)
        return lumaBuffer
    }

    /** GL thread: hand over the newest frame. */
    fun offer(frame: CaptureFrame) {
        if (finished) {
            if (frame.lumaPlane != null) lumaBusy.set(false)
            return
        }
        val prev = pending.getAndSet(frame)
        if (prev != null && prev.lumaPlane != null && frame.lumaPlane == null) {
            // Keep the sampled image of the dropped frame: attach it to the newer frame.
            val merged = CaptureFrame(frame.timestampNs, frame.pose, frame.trackingStatus,
                frame.trackingFailure, prev.lumaPlane, prev.width, prev.height, prev.rowStride,
                frame.featurePoints, frame.featureCount)
            // If the analysis thread already took the newer frame, release the sample instead.
            if (!pending.compareAndSet(frame, merged)) lumaBusy.set(false)
        }
        schedule()
    }

    private fun schedule() {
        if (scheduled.compareAndSet(false, true)) {
            try {
                executor.execute { drain() }
            } catch (e: Exception) {
                scheduled.set(false)
            }
        }
    }

    private fun drain() {
        while (true) {
            val f = pending.getAndSet(null) ?: break
            try {
                process(f)
            } catch (e: Exception) {
                Log.e(TAG, "analysis failed", e)
            } finally {
                if (f.lumaPlane != null) lumaBusy.set(false)
            }
        }
        scheduled.set(false)
        if (pending.get() != null) schedule()
    }

    private fun process(f: CaptureFrame) {
        if (finished) return
        val nowMs = f.timestampNs / 1_000_000L
        if (!engineStarted) {
            engineStarted = true
            startMs = nowMs
            logger.alerts(engine.start(nowMs))
            // ARCore pauses briefly when recording starts; do not report that as user error.
            engine.suspendUntil(nowMs + 500)
        }
        updateThermal(nowMs)

        val result = analyzer.process(f, thermalStatus, imageAnalysisEnabled = !imageSuspended)
        if (result.imageAnalysed) imageSamples++
        val out = engine.evaluate(result.signal, nowMs)
        lastSignal = result.signal
        lastState = out.state
        logger.signal(result.signal)
        logger.alerts(out.events)

        if (result.motion.newKeyframe) trail += floatArrayOf(f.pose.tx, f.pose.tz)

        if (out.changed) emit("guidanceChanged", guidanceMap(out.state))
        if (nowMs - lastStatusMs >= 250) {
            lastStatusMs = nowMs
            emit("captureStatus", statusMap(f, out.state, result.signal))
        }
        if (debugSignals() && nowMs - lastSignalEmitMs >= 200) {
            lastSignalEmitMs = nowMs
            emit("signalFrame", LogCodec.signalToMap(result.signal) + mapOf(
                "sampleInterval" to sampleInterval, "imageSuspended" to imageSuspended))
        }
        if (!maxDurationFired && nowMs - startMs >= thresholds.maxSessionMs) {
            maxDurationFired = true
            onMaxDuration()
        }
    }

    private fun updateThermal(nowMs: Long) {
        if (nowMs - lastThermalCheckMs < 1000) return
        lastThermalCheckMs = nowMs
        val status = if (Build.VERSION.SDK_INT >= 29) powerManager?.currentThermalStatus ?: 0 else 0
        thermalStatus = status
        // TDD 5.3: first warning level doubles the sampling interval, the next suspends image analysis.
        val base = thresholds.imageSampleInterval.coerceAtLeast(1)
        sampleInterval = if (status >= 2) base * 2 else base
        imageSuspended = status >= 3
    }

    private fun statusMap(f: CaptureFrame, st: GuidanceState, s: SignalFrame): Map<String, Any?> {
        val newPoints = trail.subList(trailEmitted, trail.size).map { listOf(it[0].toDouble(), it[1].toDouble()) }
        trailEmitted = trail.size
        return mapOf(
            "sessionState" to st.sessionState.name,
            "trackingStatus" to f.trackingStatus.name,
            "trackingFailure" to f.trackingFailure.name,
            "elapsedMs" to st.elapsedMs.toDouble(),
            "canStop" to st.canStop,
            "keyframeCount" to s.keyframeCount,
            "pathLengthM" to s.pathLengthM.toDouble(),
            "maxDistanceFromStartM" to s.maxDistanceFromStartM.toDouble(),
            "thermalStatus" to thermalStatus,
            "position" to listOf(f.pose.tx.toDouble(), f.pose.tz.toDouble()),
            "trailAppend" to newPoints,
            "maxSessionMs" to thresholds.maxSessionMs.toDouble(),
            "warnBeforeEndMs" to thresholds.warnBeforeEndMs.toDouble()
        )
    }

    data class Finish(
        val summary: EngineSummary,
        val lastState: GuidanceState?,
        val trail: List<FloatArray>,
        val lastSignal: SignalFrame?,
        val startMs: Long
    )

    /** Stops the engine on the analysis thread after pending work, and shuts the thread down. */
    fun finish(): Finish {
        val future = executor.submit(Callable<Finish> {
            finished = true
            pending.set(null)
            val now = lastSignal?.let { it.timestampNs / 1_000_000L } ?: startMs
            logger.alerts(engine.stop(now))
            Finish(engine.summary(now), lastState, ArrayList(trail), lastSignal, startMs)
        })
        val result = try {
            future.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "finish failed", e)
            finished = true
            Finish(engine.summary(), lastState, ArrayList(trail), lastSignal, startMs)
        }
        executor.shutdown()
        lumaBusy.set(false)
        return result
    }

    companion object {
        private const val TAG = "GuidedCapturePipeline"

        fun guidanceMap(st: GuidanceState): Map<String, Any?> = mapOf(
            "ruleId" to st.ruleId?.name,
            "severity" to st.severity.name,
            "tier" to st.ruleId?.tier,
            "sessionState" to st.sessionState.name,
            "canStop" to st.canStop,
            "escalated" to st.escalated,
            "elapsedMs" to st.elapsedMs.toDouble(),
            "coverage" to mapOf(
                "method" to st.coverage.method.name,
                "surfacesSeenFromTwoPlusAngles" to st.coverage.surfacesSeenFromTwoPlusAngles.toDouble(),
                "observedVoxelCount" to st.coverage.observedVoxelCount,
                "coveredVoxelCount" to st.coverage.coveredVoxelCount
            )
        )
    }
}
