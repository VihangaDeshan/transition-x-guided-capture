// SPDX-License-Identifier: Apache-2.0
// signals: CaptureFrame -> SignalFrame. Image fields are refreshed on sampled frames and carried
// forward between samples so that every SignalFrame is complete (TDD v1.0 section 7.2).
// The coverage model is updated with every frame's points; its navigation snapshot is refreshed
// at most every `coverageSnapshotNs` and carried forward like the image fields.
package lk.transitionx.capture.signals

import lk.transitionx.capture.core.CaptureFrame
import lk.transitionx.capture.core.CoverageMethod
import lk.transitionx.capture.core.SignalFrame
import lk.transitionx.capture.core.TrackingStatus

class SignalAnalyzer(
    motionConfig: MotionConfig = MotionConfig(),
    coverageConfig: CoverageConfig = CoverageConfig(),
    private val coverageSnapshotNs: Long = 250_000_000L,
    /** Depth readings are trusted up to this range; beyond it they are reported as absent. */
    private val maxDepthM: Float = 8f
) {
    private val motion = MotionTracker(motionConfig)
    val coverage = CoverageModel(coverageConfig)
    private var image: ImageStats? = null
    private var imageNs: Long = -1
    private var snapshot = CoverageSnapshot.EMPTY
    private var snapshotNs = Long.MIN_VALUE
    private var depthSeen = false
    private var pointsSeen = false
    private var lastDepthM: Float? = null
    private var lastDepthNs = Long.MIN_VALUE

    data class Result(val signal: SignalFrame, val motion: MotionStats, val imageAnalysed: Boolean)

    /** Which coverage definition this session can honestly report. */
    val coverageMethod: CoverageMethod
        get() = when {
            depthSeen -> CoverageMethod.VOXEL_GRID
            pointsSeen -> CoverageMethod.POINTCLOUD_ANGULAR
            else -> CoverageMethod.POSE_TRAIL
        }

    fun reset() {
        motion.reset()
        coverage.reset()
        image = null
        imageNs = -1
        snapshot = CoverageSnapshot.EMPTY
        snapshotNs = Long.MIN_VALUE
        depthSeen = false
        pointsSeen = false
        lastDepthM = null
        lastDepthNs = Long.MIN_VALUE
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

        if (trackingOk) {
            coverage.observeView(frame.pose)
            val pts = frame.featurePoints
            if (pts != null && pts.size >= 3) {
                coverage.addPoints(frame.pose, pts)
                if (frame.pointsFromDepth) depthSeen = true else pointsSeen = true
            }
            if (frame.timestampNs - snapshotNs >= coverageSnapshotNs) {
                snapshot = coverage.snapshot(frame.pose)
                snapshotNs = frame.timestampNs
            }
        }
        val depth = frame.centerDepthM
        if (depth != null && depth.isFinite() && depth > 0f && depth <= maxDepthM) {
            lastDepthM = depth
            lastDepthNs = frame.timestampNs
        }
        // A depth reading is only meaningful for about a second; afterwards it is absent, not stale.
        val distance = if (lastDepthM != null && frame.timestampNs - lastDepthNs <= 1_000_000_000L && trackingOk) lastDepthM else null

        val img = image
        val method = coverageMethod
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
            distanceToSurface = distance,
            trackingOk = trackingOk,
            failureReason = frame.trackingFailure,
            thermalStatus = thermalStatus,
            imageAgeMs = if (imageNs < 0) -1 else (frame.timestampNs - imageNs) / 1_000_000L,
            windowRotationDeg = m.windowRotationDeg,
            windowTranslationM = m.windowTranslationM,
            jitterDegPerSec = m.jitterDegPerSec,
            keyframeCount = m.keyframeCount,
            pathLengthM = m.pathLengthM,
            maxDistanceFromStartM = m.maxDistanceFromStartM,
            textureFraction = img?.textureFraction ?: -1f,
            sharpness = img?.sharpness ?: -1f,
            angularAccel = m.angularAccel,
            afState = frame.afState,
            specularPercent = img?.specularPercent ?: 0f,
            distanceFromStartM = m.distanceFromStartM,
            coverageMethodCode = when (method) {
                CoverageMethod.VOXEL_GRID -> 3
                CoverageMethod.POINTCLOUD_ANGULAR -> 2
                else -> 0
            },
            coverageObserved = snapshot.observed,
            coverageCovered = snapshot.covered,
            unseenArcDeg = snapshot.unseenArcDeg,
            unseenArcYawDeg = snapshot.unseenArcYawDeg,
            lookedUp = snapshot.lookedUp,
            lookedDown = snapshot.lookedDown,
            viewVoxelCount = snapshot.viewVoxelCount,
            viewSingleAngleFraction = snapshot.viewSingleAngleFraction,
            missingCornerYawDeg = snapshot.missingCornerYawDeg
        )
        return Result(signal, m, analysed)
    }
}
