// SPDX-License-Identifier: Apache-2.0
// signals: CaptureFrame -> SignalFrame. Image fields are refreshed on sampled frames and carried
// forward between samples so that every SignalFrame is complete (TDD v1.0 section 7.2).
package lk.transitionx.capture.signals

import lk.transitionx.capture.core.CaptureFrame
import lk.transitionx.capture.core.SignalFrame
import lk.transitionx.capture.core.TrackingStatus

class SignalAnalyzer(motionConfig: MotionConfig = MotionConfig()) {
    private val motion = MotionTracker(motionConfig)
    private var image: ImageStats? = null
    private var imageNs: Long = -1

    data class Result(val signal: SignalFrame, val motion: MotionStats, val imageAnalysed: Boolean)

    fun reset() {
        motion.reset()
        image = null
        imageNs = -1
    }

    fun process(frame: CaptureFrame, thermalStatus: Int, imageAnalysisEnabled: Boolean = true): Result {
        val trackingOk = frame.trackingStatus == TrackingStatus.TRACKING
        var analysed = false
        val luma = frame.lumaPlane
        if (imageAnalysisEnabled && luma != null && frame.width > 0 && frame.height > 0) {
            image = ImageMetrics.analyze(luma, frame.width, frame.height, frame.rowStride)
            imageNs = frame.timestampNs
            analysed = true
        }
        val m = motion.update(frame.timestampNs, frame.pose, trackingOk)
        val img = image
        val signal = SignalFrame(
            timestampNs = frame.timestampNs,
            blurScore = img?.blurScore ?: 0f,
            brightness = img?.brightness ?: 0f,
            clippedHigh = img?.clippedHigh ?: 0f,
            clippedLow = img?.clippedLow ?: 0f,
            angularVelocity = m.angularVelocity,
            linearVelocity = m.linearVelocity,
            featureCount = frame.featureCount,
            baseline = m.baseline,
            distanceToSurface = null, // Phase 2 (depth); absent rather than wrong
            trackingOk = trackingOk,
            failureReason = frame.trackingFailure,
            thermalStatus = thermalStatus,
            imageAgeMs = if (imageNs < 0) -1 else (frame.timestampNs - imageNs) / 1_000_000L,
            windowRotationDeg = m.windowRotationDeg,
            windowTranslationM = m.windowTranslationM,
            jitterDegPerSec = m.jitterDegPerSec,
            keyframeCount = m.keyframeCount,
            pathLengthM = m.pathLengthM,
            maxDistanceFromStartM = m.maxDistanceFromStartM
        )
        return Result(signal, m, analysed)
    }
}
