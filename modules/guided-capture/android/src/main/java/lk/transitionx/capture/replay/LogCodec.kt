// SPDX-License-Identifier: Apache-2.0
// replay: the line formats of poses.jsonl, quality.jsonl and alerts.jsonl. Written by the app,
// read back by the replay harness, so both sides use exactly this code.
package lk.transitionx.capture.replay

import lk.transitionx.capture.core.CapturePose
import lk.transitionx.capture.core.GuidanceRuleId
import lk.transitionx.capture.core.SessionState
import lk.transitionx.capture.core.Severity
import lk.transitionx.capture.core.SignalFrame
import lk.transitionx.capture.core.TrackingFailure
import lk.transitionx.capture.core.TrackingStatus
import lk.transitionx.capture.guidance.AlertEvent
import lk.transitionx.capture.guidance.AlertType

object LogCodec {
    fun poseLine(tNs: Long, pose: CapturePose, status: TrackingStatus, failure: TrackingFailure): String =
        JsonLite.write(linkedMapOf(
            "t" to tNs,
            "tx" to pose.tx, "ty" to pose.ty, "tz" to pose.tz,
            "qx" to pose.qx, "qy" to pose.qy, "qz" to pose.qz, "qw" to pose.qw,
            "tracking" to status.name,
            "failure" to failure.name
        ))

    fun signalToMap(s: SignalFrame): Map<String, Any?> = linkedMapOf(
        "t" to s.timestampNs,
        "blurScore" to s.blurScore,
        "brightness" to s.brightness,
        "clippedHigh" to s.clippedHigh,
        "clippedLow" to s.clippedLow,
        "angularVelocity" to s.angularVelocity,
        "linearVelocity" to s.linearVelocity,
        "featureCount" to s.featureCount,
        "baseline" to s.baseline,
        "distanceToSurface" to s.distanceToSurface,
        "trackingOk" to s.trackingOk,
        "failureReason" to s.failureReason.name,
        "thermalStatus" to s.thermalStatus,
        "imageAgeMs" to s.imageAgeMs,
        "windowRotationDeg" to s.windowRotationDeg,
        "windowTranslationM" to s.windowTranslationM,
        "jitterDegPerSec" to s.jitterDegPerSec,
        "keyframeCount" to s.keyframeCount,
        "pathLengthM" to s.pathLengthM,
        "maxDistanceFromStartM" to s.maxDistanceFromStartM,
        "textureFraction" to s.textureFraction,
        "sharpness" to s.sharpness,
        "angularAccel" to s.angularAccel,
        "afState" to s.afState,
        "specularPercent" to s.specularPercent,
        "distanceFromStartM" to s.distanceFromStartM,
        "coverageMethodCode" to s.coverageMethodCode,
        "coverageObserved" to s.coverageObserved,
        "coverageCovered" to s.coverageCovered,
        "unseenArcDeg" to s.unseenArcDeg,
        "unseenArcYawDeg" to s.unseenArcYawDeg,
        "lookedUp" to s.lookedUp,
        "lookedDown" to s.lookedDown,
        "viewVoxelCount" to s.viewVoxelCount,
        "viewSingleAngleFraction" to s.viewSingleAngleFraction,
        "missingCornerYawDeg" to s.missingCornerYawDeg
    )

    fun signalLine(s: SignalFrame): String = JsonLite.write(signalToMap(s))

    fun signalFromMap(m: Map<String, Any?>): SignalFrame {
        fun f(k: String) = (m[k] as? Number)?.toFloat() ?: 0f
        fun i(k: String) = (m[k] as? Number)?.toInt() ?: 0
        fun l(k: String, d: Long = 0) = (m[k] as? Number)?.toLong() ?: d
        return SignalFrame(
            timestampNs = l("t"),
            blurScore = f("blurScore"),
            brightness = f("brightness"),
            clippedHigh = f("clippedHigh"),
            clippedLow = f("clippedLow"),
            angularVelocity = f("angularVelocity"),
            linearVelocity = f("linearVelocity"),
            featureCount = i("featureCount"),
            baseline = f("baseline"),
            distanceToSurface = (m["distanceToSurface"] as? Number)?.toFloat(),
            trackingOk = m["trackingOk"] as? Boolean ?: false,
            failureReason = (m["failureReason"] as? String)?.let { runCatching { TrackingFailure.valueOf(it) }.getOrNull() }
                ?: TrackingFailure.NONE,
            thermalStatus = i("thermalStatus"),
            imageAgeMs = l("imageAgeMs", -1),
            windowRotationDeg = f("windowRotationDeg"),
            windowTranslationM = f("windowTranslationM"),
            jitterDegPerSec = f("jitterDegPerSec"),
            keyframeCount = i("keyframeCount"),
            pathLengthM = f("pathLengthM"),
            maxDistanceFromStartM = f("maxDistanceFromStartM"),
            textureFraction = (m["textureFraction"] as? Number)?.toFloat() ?: -1f,
            sharpness = (m["sharpness"] as? Number)?.toFloat() ?: -1f,
            // v1.2 fields. Absent in older logs: unknown must never trigger guidance on replay.
            angularAccel = f("angularAccel"),
            afState = (m["afState"] as? Number)?.toInt() ?: -1,
            specularPercent = f("specularPercent"),
            distanceFromStartM = (m["distanceFromStartM"] as? Number)?.toFloat() ?: f("maxDistanceFromStartM"),
            coverageMethodCode = i("coverageMethodCode"),
            coverageObserved = i("coverageObserved"),
            coverageCovered = i("coverageCovered"),
            unseenArcDeg = f("unseenArcDeg"),
            unseenArcYawDeg = (m["unseenArcYawDeg"] as? Number)?.toFloat(),
            lookedUp = m["lookedUp"] as? Boolean ?: true,
            lookedDown = m["lookedDown"] as? Boolean ?: true,
            viewVoxelCount = i("viewVoxelCount"),
            viewSingleAngleFraction = f("viewSingleAngleFraction"),
            missingCornerYawDeg = (m["missingCornerYawDeg"] as? Number)?.toFloat()
        )
    }

    fun signalFromLine(line: String): SignalFrame = signalFromMap(JsonLite.parseObject(line))

    fun alertToMap(e: AlertEvent): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("tMs" to e.atMs, "type" to e.type.name)
        e.ruleId?.let { m["ruleId"] = it.name; m["tier"] = it.tier }
        e.severity?.let { m["severity"] = it.name }
        if (e.type == AlertType.SHOWN || e.type == AlertType.DISMISSED) m["escalated"] = e.escalated
        e.reason?.let { m["reason"] = it }
        e.shownForMs?.let { m["shownForMs"] = it }
        e.fromState?.let { m["from"] = it.name }
        e.toState?.let { m["to"] = it.name }
        return m
    }

    fun alertLine(e: AlertEvent): String = JsonLite.write(alertToMap(e))

    fun alertFromLine(line: String): AlertEvent {
        val m = JsonLite.parseObject(line)
        return AlertEvent(
            atMs = (m["tMs"] as Number).toLong(),
            type = AlertType.valueOf(m["type"] as String),
            ruleId = (m["ruleId"] as? String)?.let { GuidanceRuleId.valueOf(it) },
            severity = (m["severity"] as? String)?.let { Severity.valueOf(it) },
            escalated = m["escalated"] as? Boolean ?: false,
            reason = m["reason"] as? String,
            shownForMs = (m["shownForMs"] as? Number)?.toLong(),
            fromState = (m["from"] as? String)?.let { SessionState.valueOf(it) },
            toState = (m["to"] as? String)?.let { SessionState.valueOf(it) }
        )
    }
}
