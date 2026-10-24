// SPDX-License-Identifier: Apache-2.0
package lk.transitionx.capture

import kotlin.math.cos
import kotlin.math.sin
import lk.transitionx.capture.core.CapturePose
import lk.transitionx.capture.core.SignalFrame
import lk.transitionx.capture.core.TrackingFailure

/** Pose at (x, 0, z) rotated by [yawDeg] about the vertical axis. */
fun poseAt(x: Float, z: Float, yawDeg: Float): CapturePose {
    val half = Math.toRadians(yawDeg.toDouble()) / 2.0
    return CapturePose(x, 0f, z, 0f, sin(half).toFloat(), 0f, cos(half).toFloat())
}

/** A SignalFrame describing a healthy capture; tests override only what they exercise. */
fun goodSignal(tMs: Long, keyframes: Int = (tMs / 300).toInt()) = SignalFrame(
    timestampNs = tMs * 1_000_000L,
    blurScore = 400f,
    brightness = 120f,
    clippedHigh = 1f,
    clippedLow = 1f,
    angularVelocity = 10f,
    linearVelocity = 0.4f,
    featureCount = 150,
    baseline = 0.05f,
    distanceToSurface = null,
    trackingOk = true,
    failureReason = TrackingFailure.NONE,
    thermalStatus = 0,
    imageAgeMs = 50,
    windowRotationDeg = 10f,
    windowTranslationM = 0.6f,
    jitterDegPerSec = 1f,
    keyframeCount = keyframes,
    pathLengthM = keyframes * 0.15f,
    maxDistanceFromStartM = minOf(3f, keyframes * 0.1f)
)
