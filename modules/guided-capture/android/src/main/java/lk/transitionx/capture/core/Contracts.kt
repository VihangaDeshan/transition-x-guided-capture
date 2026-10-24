// SPDX-License-Identifier: Apache-2.0
//
// core-model: the three data contracts of the Guided Capture design (TDD v1.0, section 7).
// This package must never import android.*, com.google.ar.* or expo.*. The JVM-only build in
// tools/engine-tests compiles it without the Android SDK, which enforces that rule in CI.
package lk.transitionx.capture.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min
import kotlin.math.sqrt

/** Plain equivalent of an ARCore Pose: translation in metres, rotation as a unit quaternion. */
data class CapturePose(
    val tx: Float, val ty: Float, val tz: Float,
    val qx: Float, val qy: Float, val qz: Float, val qw: Float
) {
    fun translationDistanceTo(other: CapturePose): Float {
        val dx = tx - other.tx
        val dy = ty - other.ty
        val dz = tz - other.tz
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Smallest rotation angle, in degrees, that takes this orientation to [other]. */
    fun angleDegreesTo(other: CapturePose): Float {
        val n1 = sqrt(qx * qx + qy * qy + qz * qz + qw * qw)
        val n2 = sqrt(other.qx * other.qx + other.qy * other.qy + other.qz * other.qz + other.qw * other.qw)
        if (n1 == 0f || n2 == 0f) return 0f
        val dot = abs((qx * other.qx + qy * other.qy + qz * other.qz + qw * other.qw) / (n1 * n2))
        val clamped = min(1.0, dot.toDouble())
        return Math.toDegrees(2.0 * acos(clamped)).toFloat()
    }

    companion object {
        val IDENTITY = CapturePose(0f, 0f, 0f, 0f, 0f, 0f, 1f)
    }
}

enum class TrackingStatus { TRACKING, PAUSED, STOPPED }

enum class TrackingFailure {
    NONE, EXCESSIVE_MOTION, INSUFFICIENT_FEATURES, INSUFFICIENT_LIGHT, BAD_STATE, CAMERA_UNAVAILABLE
}

/**
 * Layer 1 -> Layer 2. Everything extracted from one ARCore frame, with no judgement about quality.
 * Not a data class because it carries arrays (structural equality would be misleading).
 */
class CaptureFrame(
    val timestampNs: Long,
    val pose: CapturePose,
    val trackingStatus: TrackingStatus,
    val trackingFailure: TrackingFailure,
    /** Luminance (Y) plane, or null when this frame was not sampled for image analysis. */
    val lumaPlane: ByteArray?,
    val width: Int,
    val height: Int,
    /** Row stride of [lumaPlane] in bytes (>= width). */
    val rowStride: Int,
    val featurePoints: FloatArray?,
    val featureCount: Int
)

/**
 * Layer 2 -> Layer 3. Numbers only; no rule is evaluated here.
 *
 * Fields up to [thermalStatus] are exactly the TDD v1.0 contract. The fields after it are
 * additive v1.0 extensions (documented in docs/CONTRACTS.md): the decision engine receives no
 * poses, so the windowed motion figures that G2, G3, G4 and C6 need are measured here instead.
 */
data class SignalFrame(
    val timestampNs: Long,
    val blurScore: Float,
    val brightness: Float,
    val clippedHigh: Float,
    val clippedLow: Float,
    val angularVelocity: Float,
    val linearVelocity: Float,
    val featureCount: Int,
    val baseline: Float,
    val distanceToSurface: Float?,
    val trackingOk: Boolean,
    val failureReason: TrackingFailure,
    val thermalStatus: Int,
    // --- extensions ---
    /** Age of the image measurements in milliseconds; -1 when no image has been analysed yet. */
    val imageAgeMs: Long = -1,
    /** Accumulated rotation over the spin window (default 2 s), degrees. */
    val windowRotationDeg: Float = 0f,
    /** Net translation over the spin window, metres. */
    val windowTranslationM: Float = 0f,
    /** High-frequency rotation (hand shake), degrees per second. */
    val jitterDegPerSec: Float = 0f,
    val keyframeCount: Int = 0,
    val pathLengthM: Float = 0f,
    val maxDistanceFromStartM: Float = 0f,
    /** Share of textured 8x8 blocks, 0..1; -1 when unknown (logs written before v1.1). */
    val textureFraction: Float = -1f,
    /** Contrast-normalised sharpness; -1 when unknown (logs written before v1.1). */
    val sharpness: Float = -1f
)

enum class Severity { INFO, WARNING, CRITICAL }

enum class SessionState { IDLE, INITIALIZING, SCANNING, TRACKING_LOST, REVIEWING }

enum class CoverageMethod { NONE, POSE_TRAIL, POINTCLOUD_ANGULAR, VOXEL_GRID }

data class CoverageState(
    val method: CoverageMethod,
    /** 0..1. Only meaningful for POINTCLOUD_ANGULAR and VOXEL_GRID; 0 for POSE_TRAIL. */
    val surfacesSeenFromTwoPlusAngles: Float,
    val observedVoxelCount: Int,
    val coveredVoxelCount: Int
) {
    companion object {
        val POSE_TRAIL_ONLY = CoverageState(CoverageMethod.POSE_TRAIL, 0f, 0, 0)
    }
}

/**
 * Rule identifiers from TDD v1.0 section 10.1, declared in catalogue order. Priority is the tier
 * (lower is more urgent); inside a tier, declaration order breaks ties, so selection is
 * deterministic. Severity is derived from the tier and never stored per rule.
 */
enum class GuidanceRuleId(val tier: Int, val phase: Int) {
    T1(0, 1), T2(0, 1), T4(0, 1), T5(0, 1),
    G4(1, 1),
    G1(2, 1), L1(2, 1), L3(2, 1), S1(2, 1),
    G2(3, 1), L2(3, 1), T6(3, 1), S2(3, 2),
    L4(4, 2), L5(4, 2), S3(4, 2), T3(4, 2),
    G3(5, 1), C3(5, 2), C5(5, 2),
    C1(6, 2), C2(6, 2),
    C6(7, 1),
    S4(4, 3), C4(6, 3);

    val severity: Severity
        get() = when (tier) {
            0, 1 -> Severity.CRITICAL
            2, 3 -> Severity.WARNING
            else -> Severity.INFO
        }
}

/** Layer 3 -> Layer 4. Carries a rule identifier, never a rendered sentence (TDD 11.2). */
data class GuidanceState(
    val ruleId: GuidanceRuleId?,
    val severity: Severity,
    val sessionState: SessionState,
    val coverage: CoverageState,
    val canStop: Boolean,
    val escalated: Boolean,
    val elapsedMs: Long
)
