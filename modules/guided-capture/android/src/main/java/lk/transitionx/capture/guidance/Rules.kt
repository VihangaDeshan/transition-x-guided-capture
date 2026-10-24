// SPDX-License-Identifier: Apache-2.0
// guidance: the Phase 1 rule set (TDD v1.0 section 10.1) plus T3 (thermal), which the thermal
// policy in section 5.3 needs. Each rule has an enter predicate and a stay predicate; using a
// looser stay predicate is the hysteresis in section 10.4.
package lk.transitionx.capture.guidance

import lk.transitionx.capture.core.GuidanceRuleId
import lk.transitionx.capture.core.SignalFrame
import lk.transitionx.capture.core.TrackingFailure

/** Extra context a rule may need that is not in the SignalFrame itself. */
data class RuleContext(
    val nowMs: Long,
    /** Time the keyframe count last increased (or scanning started). */
    val lastKeyframeAtMs: Long,
    val canStop: Boolean
)

class Rule(
    val id: GuidanceRuleId,
    /** Whether the rule is subject to debounce; ARCore-reported conditions are not noisy. */
    val debounced: Boolean,
    val enter: (SignalFrame, RuleContext) -> Boolean,
    val stay: (SignalFrame, RuleContext) -> Boolean = enter
)

object Rules {
    /** True when the carried-forward image measurements are recent enough to act on. */
    private fun imageFresh(s: SignalFrame, t: Thresholds) = s.imageAgeMs in 0..t.imageMaxAgeMs

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
        Rule(GuidanceRuleId.L3, debounced = true,
            enter = { s, _ -> imageFresh(s, t) && s.blurScore < t.blurEnter },
            stay = { s, _ -> imageFresh(s, t) && s.blurScore < t.blurExit }),
        Rule(GuidanceRuleId.S1, debounced = true,
            enter = { s, _ ->
                (s.trackingOk && s.featureCount < t.featureEnter) ||
                    s.failureReason == TrackingFailure.INSUFFICIENT_FEATURES
            },
            stay = { s, _ ->
                (s.trackingOk && s.featureCount < t.featureExit) ||
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
