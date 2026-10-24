// SPDX-License-Identifier: Apache-2.0
// guidance: the complete rule set. Phase 1 (TDD v1.0 section 10.1), Phase 2 (G5 L4 L5 S2 S3 C1
// C2 C3 C5 T3 T7) and Phase 3 (S4 experimental, C4). Each rule has an enter predicate and a stay
// predicate; a looser stay predicate is the hysteresis of section 10.4. `sustainMs` requires the
// condition to hold continuously before the rule may activate.
package lk.transitionx.capture.guidance

import kotlin.math.abs
import lk.transitionx.capture.core.Direction
import lk.transitionx.capture.core.GuidanceRuleId
import lk.transitionx.capture.core.SignalFrame
import lk.transitionx.capture.core.TrackingFailure

/** Extra context a rule may need that is not in the SignalFrame itself. */
data class RuleContext(
    val nowMs: Long,
    /** Time the keyframe count last increased (or scanning started). */
    val lastKeyframeAtMs: Long,
    val canStop: Boolean,
    /** Time spent since SCANNING was first entered. */
    val scanningMs: Long = 0,
    /** The user has come back near the start after walking away (C5 satisfied). */
    val loopClosed: Boolean = false,
    /** A poor stretch was detected and the user has not yet been asked to redo it (T7). */
    val poorSegmentPending: Boolean = false
)

class Rule(
    val id: GuidanceRuleId,
    /** Whether the rule is subject to debounce; ARCore-reported conditions are not noisy. */
    val debounced: Boolean,
    val enter: (SignalFrame, RuleContext) -> Boolean,
    val stay: (SignalFrame, RuleContext) -> Boolean = enter,
    /** The enter condition must hold this long before the rule activates. */
    val sustainMs: Long = 0,
    /** Arrow for the interface (C1, C2, C4). */
    val direction: ((SignalFrame, RuleContext) -> Direction?)? = null
)

object Rules {
    /** True when the carried-forward image measurements are recent enough to act on. */
    private fun imageFresh(s: SignalFrame, t: Thresholds) = s.imageAgeMs in 0..t.imageMaxAgeMs

    private fun moving(s: SignalFrame, t: Thresholds) =
        s.angularVelocity > t.blurMotionDegPerSec || s.linearVelocity > t.blurMotionLinearMps

    private fun goodNow(s: SignalFrame, t: Thresholds): Boolean {
        if (!s.trackingOk) return false
        if (!imageFresh(s, t)) return true
        return s.brightness >= t.darkExit && !(s.sharpness >= 0f && s.sharpness < t.sharpnessExit)
    }

    private fun still(s: SignalFrame) = s.angularVelocity < 5f && s.linearVelocity < 0.1f

    private fun turnDirection(yaw: Float?): Direction? = when {
        yaw == null -> null
        abs(yaw) > 150f -> Direction.BEHIND
        yaw > 0f -> Direction.RIGHT
        else -> Direction.LEFT
    }

    /** Camera2 CONTROL_AF_STATE: 1 PASSIVE_SCAN, 3 ACTIVE_SCAN. */
    private fun focusing(s: SignalFrame) = s.afState == 1 || s.afState == 3

    /** Every rule the engine evaluates (all phases). */
    fun all(t: Thresholds): List<Rule> = phase1(t) + phase2and3(t)

    fun phase2and3(t: Thresholds): List<Rule> = listOf(
        // T7 - the last stretch was poor (lost / dark / blurred): ask once to film it again.
        //      Asked once conditions are good again for a second, so the advice is actionable.
        Rule(GuidanceRuleId.T7, debounced = true, sustainMs = 1000,
            enter = { s, c -> c.poorSegmentPending && goodNow(s, t) },
            // One-shot: once shown, the pending flag is cleared, so T7 cannot come back after
            // another message interrupts it (first real capture showed it 6 times otherwise).
            stay = { s, c -> s.trackingOk && c.poorSegmentPending }),
        // G5 - jerky motion: sudden change of turning speed.
        Rule(GuidanceRuleId.G5, debounced = true,
            enter = { s, _ -> s.trackingOk && s.angularAccel > t.angularAccelEnter },
            stay = { s, _ -> s.trackingOk && s.angularAccel > t.angularAccelExit }),
        // L5 - autofocus is still searching while the image is soft.
        Rule(GuidanceRuleId.L5, debounced = true, sustainMs = t.focusSustainMs,
            enter = { s, _ -> imageFresh(s, t) && focusing(s) && s.sharpness in 0f..t.sharpnessExit },
            stay = { s, _ -> imageFresh(s, t) && focusing(s) }),
        // L4 - soft image while still, well lit and detailed, focus settled: the lens is dirty.
        Rule(GuidanceRuleId.L4, debounced = true, sustainMs = t.lensSustainMs,
            enter = { s, _ ->
                imageFresh(s, t) && s.trackingOk && still(s) && !focusing(s) &&
                    s.brightness >= t.darkExit && s.textureFraction >= t.lensMinTexture &&
                    s.sharpness in 0f..t.lensSharpnessMax
            },
            stay = { s, _ -> imageFresh(s, t) && s.sharpness in 0f..t.sharpnessExit }),
        // S2 / S3 - distance to the surface in view (absent without depth: rule stays silent).
        Rule(GuidanceRuleId.S2, debounced = true,
            enter = { s, _ -> s.trackingOk && (s.distanceToSurface ?: Float.MAX_VALUE) < t.tooCloseM },
            stay = { s, _ -> s.trackingOk && (s.distanceToSurface ?: Float.MAX_VALUE) < t.tooCloseExitM }),
        Rule(GuidanceRuleId.S3, debounced = true,
            enter = { s, _ -> s.trackingOk && (s.distanceToSurface ?: 0f) > t.tooFarM },
            stay = { s, _ -> s.trackingOk && (s.distanceToSurface ?: 0f) > t.tooFarExitM }),
        // S4 (experimental) - small glare highlights in darker surroundings: glass or mirror.
        Rule(GuidanceRuleId.S4, debounced = true, sustainMs = t.reflectionSustainMs,
            enter = { s, _ ->
                imageFresh(s, t) && s.trackingOk && s.clippedHigh < t.clipHighEnter &&
                    s.specularPercent in t.specularMinPercent..t.specularMaxPercent
            },
            stay = { s, _ -> imageFresh(s, t) && s.specularPercent >= t.specularMinPercent * 0.7f }),
        // C3 - what is in view has been seen from one angle only: step sideways and look again.
        Rule(GuidanceRuleId.C3, debounced = true, sustainMs = t.needViewsSustainMs,
            enter = { s, c ->
                s.trackingOk && c.scanningMs >= t.coverageGuidanceAfterMs &&
                    s.viewVoxelCount >= t.needViewsMinVoxels && s.viewSingleAngleFraction >= t.needViewsFraction
            },
            stay = { s, _ -> s.trackingOk && s.viewSingleAngleFraction >= t.needViewsFraction * 0.8f }),
        // C5 - walked far enough and long enough but never came back near the start.
        Rule(GuidanceRuleId.C5, debounced = true,
            enter = { s, c ->
                s.trackingOk && !c.loopClosed && c.scanningMs >= t.loopAfterMs &&
                    s.maxDistanceFromStartM >= t.loopMinAwayM && s.pathLengthM >= t.stopMinPathM
            },
            stay = { s, c -> s.trackingOk && !c.loopClosed }),
        // C1 - a part of the room has never been looked at: turn towards it.
        Rule(GuidanceRuleId.C1, debounced = true,
            enter = { s, c ->
                s.trackingOk && c.scanningMs >= t.coverageGuidanceAfterMs &&
                    s.unseenArcDeg >= t.unseenArcMinDeg && abs(s.unseenArcYawDeg ?: 0f) >= t.turnMinYawDeg
            },
            stay = { s, _ -> s.trackingOk && s.unseenArcDeg >= t.unseenArcMinDeg * 0.5f && abs(s.unseenArcYawDeg ?: 0f) >= 20f },
            direction = { s, _ -> turnDirection(s.unseenArcYawDeg) }),
        // C2 - never looked up / down (ceiling-wall and floor-wall junctions).
        Rule(GuidanceRuleId.C2, debounced = true,
            enter = { s, c -> s.trackingOk && c.scanningMs >= t.lookUpDownAfterMs && (!s.lookedUp || !s.lookedDown) },
            direction = { s, _ -> if (!s.lookedUp) Direction.UP else if (!s.lookedDown) Direction.DOWN else null }),
        // C4 (Phase 3) - a corner of the room footprint has no observations.
        Rule(GuidanceRuleId.C4, debounced = true,
            enter = { s, c ->
                s.trackingOk && c.scanningMs >= t.cornerAfterMs && s.coverageObserved >= t.cornerMinVoxels &&
                    s.missingCornerYawDeg != null
            },
            direction = { s, _ -> turnDirection(s.missingCornerYawDeg) })
    )

    /**
     * Rules evaluated against signals. T1 and T2 are not listed: they are defined by the session
     * state (TRACKING_LOST and INITIALIZING) and injected by the engine.
     */
    fun phase1(t: Thresholds): List<Rule> = listOf(
        Rule(GuidanceRuleId.T4, debounced = false,
            enter = { s, _ -> s.failureReason == TrackingFailure.CAMERA_UNAVAILABLE }),
        Rule(GuidanceRuleId.T5, debounced = false,
            enter = { s, _ -> s.failureReason == TrackingFailure.BAD_STATE }),
        Rule(GuidanceRuleId.G4, debounced = true,
            enter = { s, _ ->
                s.trackingOk && s.windowRotationDeg > t.spinRotationEnterDeg &&
                    s.windowTranslationM < t.spinTranslationEnterM
            },
            stay = { s, _ ->
                s.trackingOk && s.windowRotationDeg > t.spinRotationExitDeg &&
                    s.windowTranslationM < t.spinTranslationExitM
            }),
        Rule(GuidanceRuleId.G1, debounced = true,
            enter = { s, _ -> s.trackingOk && s.angularVelocity > t.angularVelocityEnter },
            stay = { s, _ -> s.trackingOk && s.angularVelocity > t.angularVelocityExit }),
        Rule(GuidanceRuleId.L1, debounced = true,
            enter = { s, _ ->
                (imageFresh(s, t) && s.brightness < t.darkEnter) ||
                    s.failureReason == TrackingFailure.INSUFFICIENT_LIGHT
            },
            stay = { s, _ ->
                (imageFresh(s, t) && s.brightness < t.darkExit) ||
                    s.failureReason == TrackingFailure.INSUFFICIENT_LIGHT
            }),
        // L3 says "move more slowly", so it requires motion: blur while still is a lens/focus
        // problem (L4/L5), and a plain sharp surface is not blur (contrast-normalised sharpness).
        Rule(GuidanceRuleId.L3, debounced = true,
            enter = { s, _ ->
                imageFresh(s, t) && moving(s, t) &&
                    (if (s.sharpness >= 0f) s.sharpness < t.sharpnessEnter else s.blurScore < t.blurEnter)
            },
            stay = { s, _ ->
                imageFresh(s, t) &&
                    (if (s.sharpness >= 0f) s.sharpness < t.sharpnessExit else s.blurScore < t.blurExit)
            }),
        // S1 uses image texture, not the ARCore point count (0-11 points in a furnished room on the
        // A55 while tracking never failed). Dark frames also look textureless; L1 covers those.
        Rule(GuidanceRuleId.S1, debounced = true,
            enter = { s, _ ->
                (imageFresh(s, t) && s.textureFraction >= 0f && s.trackingOk &&
                    s.brightness >= t.darkExit && s.textureFraction < t.textureEnter) ||
                    s.failureReason == TrackingFailure.INSUFFICIENT_FEATURES
            },
            stay = { s, _ ->
                (imageFresh(s, t) && s.textureFraction >= 0f && s.trackingOk &&
                    s.textureFraction < t.textureExit) ||
                    s.failureReason == TrackingFailure.INSUFFICIENT_FEATURES
            }),
        Rule(GuidanceRuleId.G2, debounced = true,
            enter = { s, _ ->
                s.trackingOk && s.jitterDegPerSec > t.jitterEnter &&
                    s.linearVelocity < t.jitterMaxLinearVelocity
            },
            stay = { s, _ ->
                s.trackingOk && s.jitterDegPerSec > t.jitterExit &&
                    s.linearVelocity < t.jitterMaxLinearVelocity
            }),
        Rule(GuidanceRuleId.L2, debounced = true,
            enter = { s, _ -> imageFresh(s, t) && s.clippedHigh > t.clipHighEnter },
            stay = { s, _ -> imageFresh(s, t) && s.clippedHigh > t.clipHighExit }),
        Rule(GuidanceRuleId.T6, debounced = false,
            enter = { s, _ -> s.failureReason == TrackingFailure.EXCESSIVE_MOTION }),
        Rule(GuidanceRuleId.T3, debounced = true,
            enter = { s, _ -> s.thermalStatus >= t.thermalWarnStatus },
            stay = { s, _ -> s.thermalStatus >= t.thermalWarnStatus - 1 }),
        Rule(GuidanceRuleId.G3, debounced = true,
            enter = { s, c -> s.trackingOk && c.nowMs - c.lastKeyframeAtMs >= t.keepMovingAfterMs },
            stay = { s, c -> s.trackingOk && c.nowMs - c.lastKeyframeAtMs >= t.keepMovingAfterMs }),
        Rule(GuidanceRuleId.C6, debounced = false,
            enter = { _, c -> c.canStop })
    )
}
