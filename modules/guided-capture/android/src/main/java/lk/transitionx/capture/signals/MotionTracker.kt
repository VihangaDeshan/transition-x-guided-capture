// SPDX-License-Identifier: Apache-2.0
// signals: pose-derived motion measurement. Pure Kotlin; time is always passed in.
package lk.transitionx.capture.signals

import lk.transitionx.capture.core.CapturePose

data class MotionConfig(
    /** Velocities are measured across this window to suppress per-frame pose noise. */
    val velocityWindowNs: Long = 200_000_000L,
    /** Window for spin detection (G4). */
    val spinWindowNs: Long = 2_000_000_000L,
    /** Window for hand-shake measurement (G2). */
    val jitterWindowNs: Long = 1_000_000_000L,
    /** Rotation is accumulated over steps at least this far apart, which filters out shake. */
    val stepNs: Long = 100_000_000L,
    /** Distance that must be travelled before a new keyframe is accepted (baseline threshold). */
    val keyframeBaselineM: Float = 0.15f
)

data class MotionStats(
    val angularVelocity: Float,
    val linearVelocity: Float,
    val baseline: Float,
    val windowRotationDeg: Float,
    val windowTranslationM: Float,
    val jitterDegPerSec: Float,
    val keyframeCount: Int,
    val pathLengthM: Float,
    val maxDistanceFromStartM: Float,
    /** True on the update that accepted a new keyframe (used to extend the pose trail). */
    val newKeyframe: Boolean,
    /** |change of angular velocity| over the velocity window, deg/s^2 (G5). */
    val angularAccel: Float = 0f,
    /** Distance from the start position now, metres (C5). */
    val distanceFromStartM: Float = 0f
) {
    companion object {
        val ZERO = MotionStats(0f, 0f, 0f, 0f, 0f, 0f, 0, 0f, 0f, false)
    }
}

/**
 * Tracks pose history and produces motion signals. Holds no state beyond pose history, the
 * start pose and the last keyframe. While tracking is lost the history is cleared, because the
 * pose is frozen and the first pose after relocalisation may jump.
 */
class MotionTracker(private val config: MotionConfig = MotionConfig()) {
    private data class Sample(val tNs: Long, val pose: CapturePose)

    private val history = ArrayDeque<Sample>()
    private val velocityHistory = ArrayDeque<Pair<Long, Float>>()
    private var startPose: CapturePose? = null
    private var keyframePose: CapturePose? = null
    private var keyframeCount = 0
    private var pathLength = 0f
    private var maxDistFromStart = 0f
    private var last = MotionStats.ZERO

    fun reset() {
        history.clear()
        velocityHistory.clear()
        startPose = null
        keyframePose = null
        keyframeCount = 0
        pathLength = 0f
        maxDistFromStart = 0f
        last = MotionStats.ZERO
    }

    fun update(tNs: Long, pose: CapturePose, trackingOk: Boolean): MotionStats {
        if (!trackingOk) {
            history.clear()
            velocityHistory.clear()
            last = last.copy(angularVelocity = 0f, linearVelocity = 0f, windowRotationDeg = 0f,
                windowTranslationM = 0f, jitterDegPerSec = 0f, newKeyframe = false, angularAccel = 0f)
            return last
        }
        if (history.isNotEmpty() && tNs <= history.last().tNs) return last // out-of-order frame
        history.addLast(Sample(tNs, pose))
        val keep = maxOf(config.spinWindowNs, config.jitterWindowNs, config.velocityWindowNs)
        while (history.size > 2 && tNs - history.first().tNs > keep) history.removeFirst()

        val start = startPose ?: pose.also { startPose = it }
        val distFromStart = pose.translationDistanceTo(start)
        maxDistFromStart = maxOf(maxDistFromStart, distFromStart)

        val kf = keyframePose ?: pose.also { keyframePose = it }
        var baseline = pose.translationDistanceTo(kf)
        var newKeyframe = false
        if (baseline >= config.keyframeBaselineM) {
            keyframeCount++
            pathLength += baseline
            keyframePose = pose
            baseline = 0f
            newKeyframe = true
        }

        // Velocity across the velocity window.
        var angularVelocity = 0f
        var linearVelocity = 0f
        val ref = history.firstOrNull { tNs - it.tNs <= config.velocityWindowNs } ?: history.first()
        val dtV = (tNs - ref.tNs) / 1e9f
        if (dtV >= 0.03f) {
            angularVelocity = pose.angleDegreesTo(ref.pose) / dtV
            linearVelocity = pose.translationDistanceTo(ref.pose) / dtV
        }

        // Angular acceleration: change of the (window-smoothed) angular velocity across the window.
        var angularAccel = 0f
        if (dtV >= 0.03f) {
            velocityHistory.addLast(tNs to angularVelocity)
            while (velocityHistory.size > 2 && tNs - velocityHistory.first().first > config.velocityWindowNs * 2) velocityHistory.removeFirst()
            val refV = velocityHistory.firstOrNull { tNs - it.first <= config.velocityWindowNs } ?: velocityHistory.first()
            val dtA = (tNs - refV.first) / 1e9f
            if (dtA >= 0.1f) angularAccel = kotlin.math.abs(angularVelocity - refV.second) / dtA
        }

        // Spin window: decimated accumulated rotation and net translation.
        val spin = history.filter { tNs - it.tNs <= config.spinWindowNs }
        val windowRotation = accumulatedRotation(spin, config.stepNs)
        val windowTranslation = if (spin.isEmpty()) 0f else pose.translationDistanceTo(spin.first().pose)

        // Jitter: rotation at full rate minus rotation at the decimated rate, per second.
        val jit = history.filter { tNs - it.tNs <= config.jitterWindowNs }
        var jitter = 0f
        if (jit.size >= 3) {
            val span = (jit.last().tNs - jit.first().tNs) / 1e9f
            if (span >= 0.3f) {
                val full = accumulatedRotation(jit, 0L)
                val coarse = accumulatedRotation(jit, config.stepNs)
                jitter = ((full - coarse) / span).coerceAtLeast(0f)
            }
        }

        last = MotionStats(
            angularVelocity = angularVelocity,
            linearVelocity = linearVelocity,
            baseline = baseline,
            windowRotationDeg = windowRotation,
            windowTranslationM = windowTranslation,
            jitterDegPerSec = jitter,
            keyframeCount = keyframeCount,
            pathLengthM = pathLength,
            maxDistanceFromStartM = maxDistFromStart,
            newKeyframe = newKeyframe,
            angularAccel = angularAccel,
            distanceFromStartM = distFromStart
        )
        return last
    }

    private fun accumulatedRotation(samples: List<Sample>, stepNs: Long): Float {
        if (samples.size < 2) return 0f
        var total = 0f
        var anchor = samples.first()
        for (i in 1 until samples.size) {
            val s = samples[i]
            if (s.tNs - anchor.tNs >= stepNs || i == samples.size - 1) {
                total += anchor.pose.angleDegreesTo(s.pose)
                anchor = s
            }
        }
        return total
    }
}
