// SPDX-License-Identifier: Apache-2.0
package lk.transitionx.capture

import lk.transitionx.capture.core.GuidanceRuleId
import lk.transitionx.capture.core.SessionState
import lk.transitionx.capture.core.Severity
import lk.transitionx.capture.core.SignalFrame
import lk.transitionx.capture.core.TrackingFailure
import lk.transitionx.capture.guidance.AlertType
import lk.transitionx.capture.guidance.EngineOutput
import lk.transitionx.capture.guidance.GuidanceEngine
import lk.transitionx.capture.guidance.PermittedRules
import lk.transitionx.capture.guidance.Thresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceEngineTest {
    private val step = 33L // ~30 Hz

    /** Starts an engine and drives it into SCANNING past the settling period; returns the time. */
    private fun scanning(engine: GuidanceEngine, t0: Long = 0): Long {
        engine.start(t0)
        var t = t0
        // 1 s stable tracking to enter SCANNING, then 1 s settle
        while (t < t0 + 2_100) { engine.evaluate(goodSignal(t), t); t += step }
        assertEquals(SessionState.SCANNING, engine.sessionState)
        return t
    }

    private fun drive(engine: GuidanceEngine, from: Long, durationMs: Long, signal: (Long) -> SignalFrame): Pair<Long, EngineOutput> {
        var t = from
        var out: EngineOutput? = null
        while (t < from + durationMs) { out = engine.evaluate(signal(t), t); t += step }
        return t to out!!
    }

    @Test fun initialising_shows_only_T2_even_with_bad_signals() {
        val e = GuidanceEngine()
        e.start(0)
        val bad = { t: Long -> goodSignal(t).copy(trackingOk = false, blurScore = 5f, brightness = 5f, featureCount = 0) }
        val (_, out) = drive(e, 0, 3_000, bad)
        assertEquals(SessionState.INITIALIZING, out.state.sessionState)
        assertEquals(GuidanceRuleId.T2, out.state.ruleId)
        assertEquals(Severity.CRITICAL, out.state.severity)
    }

    @Test fun enters_scanning_after_stable_tracking_and_settles_before_warning() {
        val e = GuidanceEngine()
        e.start(0)
        val blurry = { t: Long -> goodSignal(t).copy(blurScore = 20f) }
        var t = 0L
        var firstScanningAt = -1L
        var firstL3At = -1L
        while (t < 4_000) {
            val o = e.evaluate(blurry(t), t)
            if (firstScanningAt < 0 && o.state.sessionState == SessionState.SCANNING) firstScanningAt = t
            if (firstL3At < 0 && o.state.ruleId == GuidanceRuleId.L3) firstL3At = t
            t += step
        }
        assertTrue(firstScanningAt in 990..1_100)
        // T2 holds until scanning; then 1 s of settling; then L3
        assertTrue("L3 at $firstL3At", firstL3At >= firstScanningAt + 1_000)
        assertTrue(firstL3At <= firstScanningAt + 1_200)
    }

    @Test fun spinning_beats_every_quality_warning() {
        val e = GuidanceEngine()
        val t = scanning(e)
        val (_, out) = drive(e, t, 500) { tt ->
            goodSignal(tt).copy(windowRotationDeg = 60f, windowTranslationM = 0.02f, angularVelocity = 80f, blurScore = 20f, featureCount = 5)
        }
        assertEquals(GuidanceRuleId.G4, out.state.ruleId)
        assertEquals(Severity.CRITICAL, out.state.severity)
    }

    @Test fun debounce_ignores_two_noisy_frames() {
        val e = GuidanceEngine()
        var t = scanning(e)
        e.evaluate(goodSignal(t).copy(blurScore = 10f), t); t += step
        e.evaluate(goodSignal(t).copy(blurScore = 10f), t); t += step
        val (_, out) = drive(e, t, 2_000) { goodSignal(it) }
        assertNull(out.state.ruleId)
    }

    @Test fun hysteresis_keeps_warning_between_enter_and_exit_thresholds() {
        val e = GuidanceEngine()
        var t = scanning(e)
        var (t2, out) = drive(e, t, 300) { goodSignal(it).copy(blurScore = 50f) }
        assertEquals(GuidanceRuleId.L3, out.state.ruleId)
        // 120 is above enter (100) but below exit (150): stays
        val r = drive(e, t2, 3_000) { goodSignal(it).copy(blurScore = 120f) }
        assertEquals(GuidanceRuleId.L3, r.second.state.ruleId)
        // a fresh engine at 120 never warns
        val fresh = GuidanceEngine()
        t = scanning(fresh)
        assertNull(drive(fresh, t, 3_000) { goodSignal(it).copy(blurScore = 120f) }.second.state.ruleId)
    }

    @Test fun message_stays_at_least_min_display_time() {
        val e = GuidanceEngine()
        var t = scanning(e)
        var shownAt = -1L
        while (shownAt < 0) {
            val o = e.evaluate(goodSignal(t).copy(featureCount = 3), t)
            if (o.state.ruleId == GuidanceRuleId.S1) shownAt = t
            t += step
        }
        // condition clears immediately
        var hiddenAt = -1L
        while (hiddenAt < 0 && t < shownAt + 5_000) {
            val o = e.evaluate(goodSignal(t), t)
            if (o.state.ruleId == null) hiddenAt = t
            t += step
        }
        assertTrue("shown for ${hiddenAt - shownAt}", hiddenAt - shownAt >= 1_500)
        assertTrue(hiddenAt - shownAt < 1_600)
    }

    @Test fun cooldown_limits_repeats_and_third_show_is_escalated() {
        val e = GuidanceEngine()
        var t = scanning(e)
        val t0 = t
        val escalations = ArrayList<Boolean>()
        var shows = 0
        val end = t + 9_500
        var prev: GuidanceRuleId? = null
        // toggle a dark scene on/off every second; within 10 s at most 3 shows
        while (t < end) {
            val dark = (((t - t0) / 1_000) % 2L) == 0L
            val o = e.evaluate(goodSignal(t).copy(brightness = if (dark) 10f else 120f), t)
            if (o.state.ruleId == GuidanceRuleId.L1 && prev != GuidanceRuleId.L1) { shows++; escalations += o.state.escalated }
            prev = o.state.ruleId
            t += step
        }
        assertEquals(3, shows)
        assertEquals(listOf(false, false, true), escalations)
    }

    @Test fun tracking_loss_shows_T6_in_grace_then_T1_exclusively() {
        val e = GuidanceEngine()
        var t = scanning(e)
        val lost = { tt: Long -> goodSignal(tt).copy(trackingOk = false, failureReason = TrackingFailure.EXCESSIVE_MOTION, blurScore = 5f) }
        val o1 = e.evaluate(lost(t), t); t += step
        assertEquals(GuidanceRuleId.T6, o1.state.ruleId) // not debounced: ARCore says so
        val (t2, o2) = drive(e, t, 700, lost)
        assertEquals(SessionState.TRACKING_LOST, o2.state.sessionState)
        assertEquals(GuidanceRuleId.T1, o2.state.ruleId) // pre-empts T6 despite min display
        // relocalised: back to scanning after stable period
        val (_, o3) = drive(e, t2, 2_500) { goodSignal(it) }
        assertEquals(SessionState.SCANNING, o3.state.sessionState)
        assertNull(o3.state.ruleId)
        val s = e.summary(t2 + 2_500)
        assertEquals(1, s.trackingLostCount)
        assertTrue(s.trackingLostTotalMs in 700..1_300)
    }

    @Test fun keep_moving_after_three_seconds_without_keyframe_and_clears_on_keyframe() {
        val e = GuidanceEngine()
        var t = scanning(e)
        val (t2, o) = drive(e, t, 3_300) { goodSignal(it, keyframes = 0) }
        assertEquals(GuidanceRuleId.G3, o.state.ruleId)
        assertEquals(Severity.INFO, o.state.severity)
        val (_, o2) = drive(e, t2, 1_700) { goodSignal(it, keyframes = ((it - t2) / 300).toInt() + 1) }
        assertNull(o2.state.ruleId)
    }

    @Test fun can_stop_is_latched_and_shown_when_nothing_else_applies() {
        val th = Thresholds(stopMinScanMs = 5_000, stopMinPathM = 1f, stopMinKeyframes = 5, stopMinExtentM = 0.5f)
        val e = GuidanceEngine(th)
        var t = scanning(e)
        var kf = 0
        val (t2, o) = drive(e, t, 4_000) { kf++; goodSignal(it, keyframes = kf / 10) }
        assertEquals(GuidanceRuleId.C6, o.state.ruleId)
        assertTrue(o.state.canStop)
        // a blur problem outranks the positive message, canStop stays true
        val (_, o2) = drive(e, t2, 2_000) { kf++; goodSignal(it, keyframes = kf / 10).copy(blurScore = 10f) }
        assertEquals(GuidanceRuleId.L3, o2.state.ruleId)
        assertTrue(o2.state.canStop)
    }

    @Test fun stale_image_measurements_are_ignored() {
        val e = GuidanceEngine()
        val t = scanning(e)
        val (_, o) = drive(e, t, 2_000) { goodSignal(it).copy(brightness = 5f, imageAgeMs = 5_000) }
        assertNull(o.state.ruleId)
    }

    @Test fun thermal_warning() {
        val e = GuidanceEngine()
        val t = scanning(e)
        val (_, o) = drive(e, t, 500) { goodSignal(it).copy(thermalStatus = 3) }
        assertEquals(GuidanceRuleId.T3, o.state.ruleId)
    }

    @Test fun lower_priority_active_rules_are_logged_as_suppressed() {
        val e = GuidanceEngine()
        val t = scanning(e)
        val events = ArrayList<lk.transitionx.capture.guidance.AlertEvent>()
        var tt = t
        while (tt < t + 500) {
            events += e.evaluate(goodSignal(tt).copy(windowRotationDeg = 60f, windowTranslationM = 0f, blurScore = 10f), tt).events
            tt += step
        }
        assertTrue(events.any { it.type == AlertType.SHOWN && it.ruleId == GuidanceRuleId.G4 })
        assertTrue(events.any { it.type == AlertType.SUPPRESSED && it.ruleId == GuidanceRuleId.L3 && it.reason == "lower_priority" })
        assertEquals(1, events.count { it.type == AlertType.SUPPRESSED && it.ruleId == GuidanceRuleId.L3 })
    }

    @Test fun same_inputs_same_outputs() {
        fun run(): List<String> {
            val e = GuidanceEngine()
            e.start(0)
            val out = ArrayList<String>()
            var t = 0L
            var i = 0
            while (t < 20_000) {
                val s = goodSignal(t, keyframes = i / 15).copy(
                    blurScore = if ((i / 40) % 3 == 0) 30f else 300f,
                    windowRotationDeg = if ((i / 90) % 4 == 1) 70f else 5f,
                    windowTranslationM = if ((i / 90) % 4 == 1) 0.01f else 0.8f,
                    trackingOk = (i / 200) % 7 != 3
                )
                out += e.evaluate(s, t).state.toString()
                t += step; i++
            }
            return out
        }
        assertEquals(run(), run())
    }

    @Test fun permitted_table_never_mixes_blocking_states() {
        assertEquals(setOf(GuidanceRuleId.T2, GuidanceRuleId.T4, GuidanceRuleId.T5), PermittedRules.of(SessionState.INITIALIZING))
        assertEquals(setOf(GuidanceRuleId.T1, GuidanceRuleId.T4, GuidanceRuleId.T5), PermittedRules.of(SessionState.TRACKING_LOST))
        assertFalse(GuidanceRuleId.T1 in PermittedRules.of(SessionState.SCANNING))
        assertTrue(PermittedRules.of(SessionState.IDLE).isEmpty())
    }

    @Test fun stop_dismisses_and_moves_to_reviewing() {
        val e = GuidanceEngine()
        val t = scanning(e)
        drive(e, t, 500) { goodSignal(it).copy(blurScore = 5f) }
        val ev = e.stop(t + 600)
        assertTrue(ev.any { it.type == AlertType.DISMISSED && it.ruleId == GuidanceRuleId.L3 })
        assertEquals(SessionState.REVIEWING, e.sessionState)
        assertNull(e.evaluate(goodSignal(t + 700).copy(blurScore = 5f), t + 700).state.ruleId)
        assertEquals(1, e.summary(t + 700).shownByRule[GuidanceRuleId.L3])
    }
}
