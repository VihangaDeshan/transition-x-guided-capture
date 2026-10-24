// SPDX-License-Identifier: Apache-2.0
// export: platform-neutral documents of the export package (TDD v1.0 section 15). Pure Kotlin;
// the Android layer only supplies device facts and writes the strings to disk.
package lk.transitionx.capture.export

import lk.transitionx.capture.core.CoverageState
import lk.transitionx.capture.guidance.EngineSummary

object ExportDocs {
    /** Bump on any change to the package layout or field meaning; record it in docs/EXPORT_FORMAT.md. */
    const val FORMAT_VERSION = "1.2"

    data class DeviceInfo(
        val manufacturer: String,
        val model: String,
        val osVersion: String,
        val sdkInt: Int,
        val arcoreApkVersion: String?
    )

    data class CameraInfo(
        val imageWidth: Int,
        val imageHeight: Int,
        val textureWidth: Int,
        val textureHeight: Int,
        val fps: Int,
        val fx: Float, val fy: Float, val cx: Float, val cy: Float
    )

    data class SessionInfo(
        val sessionId: String,
        val createdAtIso: String,
        val durationMs: Long,
        val poseCount: Long,
        val signalCount: Long,
        val imageSampleCount: Long,
        val interruptions: Int,
        val recordingMode: String,
        val recordingOk: Boolean,
        val recordingError: String?,
        val videoFile: String?,
        val endReason: String
    )

    data class DepthInfo(val supported: Boolean, val used: Boolean)

    data class TrailInfo(
        val pathLengthM: Float,
        val keyframeCount: Int,
        val maxDistanceFromStartM: Float,
        /** Keyframe positions on the floor plane (x, z), metres. */
        val points: List<FloatArray>
    )

    enum class CaptureVerdict { GOOD, CHECK, INCOMPLETE }

    /**
     * Supports the accept / re-record decision (FR-11/12). Heuristic until calibrated with Rysera's
     * pass/fail labels.
     */
    fun verdict(summary: EngineSummary, session: SessionInfo): CaptureVerdict = when {
        !session.recordingOk -> CaptureVerdict.INCOMPLETE
        session.durationMs < 10_000 -> CaptureVerdict.INCOMPLETE
        summary.canStop && summary.trackingLostCount <= 2 -> CaptureVerdict.GOOD
        else -> CaptureVerdict.CHECK
    }

    fun manifest(
        session: SessionInfo,
        device: DeviceInfo,
        camera: CameraInfo?,
        coverage: CoverageState,
        appVersion: String,
        thresholdsVersion: String,
        depth: DepthInfo? = null
    ): Map<String, Any?> = linkedMapOf(
        "formatVersion" to FORMAT_VERSION,
        "sessionId" to session.sessionId,
        "createdAt" to session.createdAtIso,
        "device" to linkedMapOf(
            "manufacturer" to device.manufacturer,
            "model" to device.model,
            "osVersion" to device.osVersion,
            "sdkInt" to device.sdkInt,
            "arcoreApkVersion" to device.arcoreApkVersion
        ),
        "camera" to camera?.let {
            linkedMapOf(
                "imageWidth" to it.imageWidth, "imageHeight" to it.imageHeight,
                "textureWidth" to it.textureWidth, "textureHeight" to it.textureHeight,
                "fps" to it.fps,
                "fx" to it.fx, "fy" to it.fy, "cx" to it.cx, "cy" to it.cy,
                "intrinsicsFor" to "cpu_image",
                // Frames are stored as the sensor delivers them (landscape), whatever the phone's
                // orientation; poses are camera sensor poses, so they match the stored frames.
                "imageOrientation" to "sensor"
            )
        },
        "session" to linkedMapOf(
            "durationMs" to session.durationMs,
            "poseCount" to session.poseCount,
            "signalCount" to session.signalCount,
            "imageSampleCount" to session.imageSampleCount,
            "interruptions" to session.interruptions,
            "endReason" to session.endReason
        ),
        "recording" to linkedMapOf(
            "mode" to session.recordingMode,
            "ok" to session.recordingOk,
            "error" to session.recordingError,
            "file" to session.videoFile,
            "videoWidth" to camera?.imageWidth,
            "videoHeight" to camera?.imageHeight
        ),
        "coverage" to linkedMapOf(
            "method" to coverage.method.name,
            "surfacesSeenFromTwoPlusAngles" to if (coverage.method.name == "POSE_TRAIL") null else coverage.surfacesSeenFromTwoPlusAngles
        ),
        "depth" to depth?.let { linkedMapOf("supported" to it.supported, "used" to it.used) },
        "poseConvention" to linkedMapOf(
            "frame" to "ARCore world, right-handed, Y up, metres",
            "pose" to "camera sensor pose (Camera.getPose), quaternion x,y,z,w",
            "timestamp" to "Frame.getTimestamp, nanoseconds"
        ),
        "files" to listOf("session.mp4", "poses.jsonl", "quality.jsonl", "alerts.jsonl", "coverage.json",
            "summary.json", "manifest.json", "status.json", PackageIntegrity.CHECKSUM_FILE),
        "integrity" to linkedMapOf(
            "algorithm" to "SHA-256",
            "file" to PackageIntegrity.CHECKSUM_FILE,
            "verify" to "sha256sum -c ${PackageIntegrity.CHECKSUM_FILE}",
            "writtenAt" to "accept"
        ),
        "app" to linkedMapOf("version" to appVersion, "thresholdsVersion" to thresholdsVersion)
    )

    /**
     * [voxels]: x, y, z (voxel centre, metres), parallax degrees, hits. A voxel is covered when its
     * parallax is at least the session's minParallaxDeg.
     */
    fun coverage(state: CoverageState, trail: TrailInfo, voxels: List<FloatArray> = emptyList()): Map<String, Any?> = linkedMapOf(
        "method" to state.method.name,
        "surfacesSeenFromTwoPlusAngles" to if (state.method.name == "POSE_TRAIL") null else state.surfacesSeenFromTwoPlusAngles,
        "observedVoxelCount" to state.observedVoxelCount,
        "coveredVoxelCount" to state.coveredVoxelCount,
        "note" to if (state.method.name == "POSE_TRAIL") "POSE_TRAIL reports the walked path only; no completion percentage is claimed."
            else "Share of observed 20 cm voxels seen along two directions at least minParallaxDeg apart (parallax), not a direction count.",
        "voxelFields" to listOf("x", "y", "z", "parallaxDeg", "hits"),
        "voxels" to voxels.map { v -> listOf(v[0], v[1], v[2], v[3], v[4]) },
        "trail" to linkedMapOf(
            "pathLengthM" to trail.pathLengthM,
            "keyframeCount" to trail.keyframeCount,
            "maxDistanceFromStartM" to trail.maxDistanceFromStartM,
            "pointsXZ" to trail.points.map { listOf(it[0], it[1]) }
        )
    )

    fun summary(summary: EngineSummary, session: SessionInfo, trail: TrailInfo, coverage: CoverageState): Map<String, Any?> =
        linkedMapOf(
            "sessionId" to session.sessionId,
            "durationMs" to session.durationMs,
            "verdict" to verdict(summary, session).name,
            "canStop" to summary.canStop,
            "trackingLostCount" to summary.trackingLostCount,
            "trackingLostTotalMs" to summary.trackingLostTotalMs,
            "warningsByRule" to summary.shownByRule.entries.sortedBy { it.key.ordinal }.associate { it.key.name to it.value },
            "suppressedByRule" to summary.suppressedByRule.entries.sortedBy { it.key.ordinal }.associate { it.key.name to it.value },
            "coverageMethod" to coverage.method.name,
            "coverageFraction" to if (coverage.method.name == "POSE_TRAIL") null else coverage.surfacesSeenFromTwoPlusAngles,
            "coverageObserved" to coverage.observedVoxelCount,
            "loopClosed" to summary.loopClosed,
            "pathLengthM" to trail.pathLengthM,
            "keyframeCount" to trail.keyframeCount,
            "maxDistanceFromStartM" to trail.maxDistanceFromStartM,
            "interruptions" to session.interruptions,
            "recordingOk" to session.recordingOk,
            "recordingError" to session.recordingError,
            "endReason" to session.endReason
        )
}
