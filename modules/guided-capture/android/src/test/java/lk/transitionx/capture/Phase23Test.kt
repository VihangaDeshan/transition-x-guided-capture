// SPDX-License-Identifier: Apache-2.0
package lk.transitionx.capture

import kotlin.math.cos
import kotlin.math.sin
import lk.transitionx.capture.core.CapturePose
import lk.transitionx.capture.core.Direction
import lk.transitionx.capture.core.GuidanceRuleId
import lk.transitionx.capture.core.SessionState
import lk.transitionx.capture.core.SignalFrame
import lk.transitionx.capture.guidance.AlertType
import lk.transitionx.capture.guidance.EngineOutput
import lk.transitionx.capture.guidance.GuidanceEngine
import lk.transitionx.capture.guidance.Thresholds
import lk.transitionx.capture.signals.CoverageModel
import lk.transitionx.capture.signals.ImageMetrics
import lk.transitionx.capture.signals.MotionTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic square room, walls at x,z = +/-2 m, height 0..2.5 m, densely sampled. */
private object Room {
    val wallPoints: FloatArray by lazy {
        val pts = ArrayList<Float>()
        var a = -2f
        while (a <= 2f) {
            var y = 0.05f
            while (y <= 2.45f) {
                for ((x, z) in listOf(a to -2f, a to 2f, -2f to a, 2f to a)) { pts += x; pts += y; pts += z }
                y += 0.1f
            }
            a += 0.1f
        }
        pts.toFloatArray()
    }

    /** Points of the room visible within the camera's view cone (half-angle 30 deg). */
    fun visible(cam: CapturePose): FloatArray {
        val f = cam.forward()
        val out = ArrayList<Float>()
        val p = wallPoints
        var i = 0
        while (i < p.size) {
            val dx = p[i] - cam.tx; val dy = p[i + 1] - cam.ty; val dz = p[i + 2] - cam.tz
            val d = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            if ((dx * f[0] + dy * f[1] + dz * f[2]) / d > 0.866f) { out += p[i]; out += p[i + 1]; out += p[i + 2] }
            i += 3
        }
        return out.toFloatArray()
    }
}

/** Camera at (x, 1.4, z), looking horizontally towards yaw (0 = -Z, 90 = +X). */
private fun camAt(x: Float, z: Float, yawDeg: Float, pitchDeg: Float = 0f): CapturePose {
    // yaw about +Y: rotating -Z towards +X means a negative rotation about +Y
    val y = Math.toRadians(-yawDeg.toDouble()) / 2
    val p = Math.toRadians(pitchDeg.toDouble()) / 2
    // q = qYaw * qPitch (pitch about +X)
    val qy = floatArrayOf(0f, sin(y).toFloat(), 0f, cos(y).toFloat())
    val qp = floatArrayOf(sin(p).toFloat(), 0f, 0f, cos(p).toFloat())
    val w = qy[3] * qp[3] - (qy[0] * qp[0] + qy[1] * qp[1] + qy[2] * qp[2])
    val vx = qy[3] * qp[0] + qp[3] * qy[0] + (qy[1] * qp[2] - qy[2] * qp[1])
    val vy = qy[3] * qp[1] + qp[3] * qy[1] + (qy[2] * qp[0] - qy[0] * qp[2])
    val vz = qy[3] * qp[2] + qp[3] * qy[2] + (qy[0] * qp[1] - qy[1] * qp[0])
    return CapturePose(x, 1.4f, z, vx, vy, vz, w)
}

class CoverageModelTest {
    @Test fun pose_forward_follows_yaw_and_pitch() {
        val f0 = camAt(0f, 0f, 0f).forward()
        assertEquals(-1f, f0[2], 1e-4f)
        val f90 = camAt(0f, 0f, 90f).forward()
        assertEquals(1f, f90[0], 1e-4f) // yaw 90 looks along +X
        val up = camAt(0f, 0f, 0f, 45f).forward()
        assertTrue(up[1] > 0.69f)
        assertEquals(90f, CoverageModel.signedYawDeg(0f, -1f, 1f, 0f), 1e-3f)   // +X is to the right
        assertEquals(-90f, CoverageModel.signedYawDeg(0f, -1f, -1f, 0f), 1e-3f)
    }

    @Test fun spinning_in_place_gives_no_coverage_walking_does() {
        val spin = CoverageModel()
        for (k in 0 until 360 step 5) {
            val cam = camAt(0f, 0f, k.toFloat())
            spin.observeView(cam)
            spin.addPoints(cam, Room.visible(cam))
        }
        assertTrue("observed ${spin.observed}", spin.observed > 300)
        assertEquals(0, spin.covered) // seen from one place only: no parallax at all

        val walk = CoverageModel()
        // walk a 1 m-radius circle, always facing outward and slightly sideways
        for (k in 0 until 720 step 5) {
            val a = Math.toRadians(k.toDouble())
            val x = (1.0 * sin(a)).toFloat(); val z = (-1.0 * cos(a)).toFloat()
            val cam = camAt(x, z, k.toFloat() + if ((k / 90) % 2 == 0) 25f else -25f)
            walk.observeView(cam)
            walk.addPoints(cam, Room.visible(cam))
        }
        val snap = walk.snapshot(camAt(0f, 0f, 0f))
        assertTrue("walk coverage ${snap.fraction}", snap.fraction > 0.4f)
        assertEquals(0f, snap.unseenArcDeg, 0f)
    }

    @Test fun unseen_arc_points_the_right_way() {
        val m = CoverageModel()
        // looked only towards yaw -90..+60 (left and front)
        for (k in -90..60 step 10) m.observeView(camAt(0f, 0f, k.toFloat()))
        val s = m.snapshot(camAt(0f, 0f, 0f))
        assertTrue(s.unseenArcDeg >= 180f)
        assertTrue("yaw ${s.unseenArcYawDeg}", (s.unseenArcYawDeg ?: 0f) > 90f) // the gap is behind-right
        assertFalse(s.lookedUp)
        m.observeView(camAt(0f, 0f, 0f, 50f))
        assertTrue(m.snapshot(camAt(0f, 0f, 0f)).lookedUp)
    }

    @Test fun view_of_single_angle_surfaces_is_measured() {
        val m = CoverageModel()
        val cam = camAt(0f, 0f, 0f)
        repeat(10) { m.addPoints(cam, Room.visible(cam)) }
        val s = m.snapshot(cam)
        assertTrue(s.viewVoxelCount > 20)
        assertEquals(1f, s.viewSingleAngleFraction, 1e-6f)
    }

    @Test fun missing_corner_is_found() {
        val m = CoverageModel()
        // observe three walls fully but never the corner region at (+2, +2)
        val pts = Room.wallPoints.toList().chunked(3).filter { (x, _, z) -> !(x > 1.0f && z > 1.0f) }.flatten().toFloatArray()
        val cam = camAt(0f, 0f, 0f)
        m.addPoints(cam, pts); m.addPoints(cam, pts)
        val s = m.snapshot(cam)
        val yaw = s.missingCornerYawDeg
        assertTrue("corner yaw $yaw", yaw != null && yaw > 90f) // (+2,+2) is behind-right of a camera facing -Z
    }

    @Test fun voxel_cap_bounds_memory() {
        val m = CoverageModel(lk.transitionx.capture.signals.CoverageConfig(maxVoxels = 50))
        val cam = camAt(0f, 0f, 0f)
        m.addPoints(cam, Room.wallPoints)
        assertEquals(50, m.observed)
    }
}

class Phase23SignalsTest {
    @Test fun glare_highlights_count_as_specular_but_a_window_does_not() {
        val w = 160; val h = 120
        val glare = ByteArray(w * h) { 90 }
        for (b in 0 until 40) { val bx = (b * 37) % 20; val by = (b * 11) % 15; glare[(by * 8 + 3) * w + bx * 8 + 3] = 255.toByte() }
        val st = ImageMetrics.analyzeDownsampled(glare)
        assertTrue("specular ${st.specularPercent}", st.specularPercent in 10f..15f)
        val window = ByteArray(w * h) { i -> if (i < w * h / 3) 255.toByte() else 90 }
        val sw = ImageMetrics.analyzeDownsampled(window)
        assertEquals(0f, sw.specularPercent, 0.01f) // saturated but bright blocks: that is L2, not S4
        assertTrue(sw.clippedHigh > 30f)
    }

    @Test fun angular_acceleration_spikes_on_a_jerk() {
        val m = MotionTracker()
        var yaw = 0f
        var maxSmooth = 0f
        var maxJerk = 0f
        for (i in 0..90) { // 3 s steady 20 deg/s
            yaw += 20f / 30
            maxSmooth = maxOf(maxSmooth, m.update(i * 33_333_333L, poseAt(0f, 0f, yaw), true).angularAccel)
        }
        for (i in 91..110) { // sudden 120 deg/s
            yaw += 120f / 30
            maxJerk = maxOf(maxJerk, m.update(i * 33_333_333L, poseAt(0f, 0f, yaw), true).angularAccel)
        }
        assertTrue("smooth $maxSmooth", maxSmooth < 100f)
        assertTrue("jerk $maxJerk", maxJerk > 400f)
    }
}

class Phase23RulesTest {
    private val step = 33L

    private fun scanning(e: GuidanceEngine): Long {
        e.start(0)
        var t = 0L
        while (t < 2_100) { e.evaluate(goodSignal(t), t); t += step }
        assertEquals(SessionState.SCANNING, e.sessionState)
        return t
    }

    private fun drive(e: GuidanceEngine, from: Long, ms: Long, f: (Long) -> SignalFrame): Pair<Long, EngineOutput> {
        var t = from; var out: EngineOutput? = null
        while (t < from + ms) { out = e.evaluate(f(t), t); t += step }
        return t to out!!
    }

    private fun shows(e: GuidanceEngine, from: Long, ms: Long, f: (Long) -> SignalFrame): List<GuidanceRuleId> {
        val ids = ArrayList<GuidanceRuleId>()
        var t = from
        while (t < from + ms) { e.evaluate(f(t), t).events.filter { it.type == AlertType.SHOWN }.forEach { ids += it.ruleId!! }; t += step }
        return ids
    }

    @Test fun g5_jerky_motion() {
        val e = GuidanceEngine(); val t = scanning(e)
        assertEquals(GuidanceRuleId.G5, drive(e, t, 400) { goodSignal(it).copy(angularAccel = 600f) }.second.state.ruleId)
    }

    @Test fun l4_dirty_lens_needs_five_still_seconds_and_not_while_moving() {
        val e = GuidanceEngine(); var t = scanning(e)
        val soft = { tt: Long -> goodSignal(tt).copy(sharpness = 0.6f, angularVelocity = 1f, linearVelocity = 0.02f) }
        val (t2, o1) = drive(e, t, 4_000, soft)
        assertNull(o1.state.ruleId?.takeIf { it == GuidanceRuleId.L4 })
        val (_, o2) = drive(e, t2, 1_500, soft)
        assertEquals(GuidanceRuleId.L4, o2.state.ruleId)
        // the same softness while turning is motion blur (L3), never "clean the lens"
        val e2 = GuidanceEngine(); t = scanning(e2)
        val ids = shows(e2, t, 7_000) { goodSignal(it).blurred(0.6f) }
        assertTrue(GuidanceRuleId.L3 in ids)
        assertFalse(GuidanceRuleId.L4 in ids)
    }

    @Test fun l5_waits_for_focus() {
        val e = GuidanceEngine(); val t = scanning(e)
        val focusing = { tt: Long -> goodSignal(tt).copy(afState = 1, sharpness = 1.1f) }
        assertEquals(GuidanceRuleId.L5, drive(e, t, 1_600, focusing).second.state.ruleId)
        val e2 = GuidanceEngine(); val t2 = scanning(e2)
        assertFalse(GuidanceRuleId.L5 in shows(e2, t2, 3_000) { goodSignal(it).copy(afState = 2, sharpness = 1.1f) }) // 2 = focused
    }

    @Test fun s2_s3_distance_and_silent_without_depth() {
        val e = GuidanceEngine(); val t = scanning(e)
        assertEquals(GuidanceRuleId.S2, drive(e, t, 400) { goodSignal(it).copy(distanceToSurface = 0.3f) }.second.state.ruleId)
        val e2 = GuidanceEngine(); val t2 = scanning(e2)
        assertEquals(GuidanceRuleId.S3, drive(e2, t2, 400) { goodSignal(it).copy(distanceToSurface = 5.5f) }.second.state.ruleId)
        val e3 = GuidanceEngine(); val t3 = scanning(e3)
        val ids = shows(e3, t3, 3_000) { goodSignal(it).copy(distanceToSurface = null) }
        assertFalse(GuidanceRuleId.S2 in ids || GuidanceRuleId.S3 in ids)
    }

    @Test fun s4_reflection_is_experimental_and_sustained() {
        val e = GuidanceEngine(); val t = scanning(e)
        val (t2, o) = drive(e, t, 1_000) { goodSignal(it).copy(specularPercent = 6f) }
        assertFalse(o.state.ruleId == GuidanceRuleId.S4)
        assertEquals(GuidanceRuleId.S4, drive(e, t2, 800) { goodSignal(it).copy(specularPercent = 6f) }.second.state.ruleId)
    }

    @Test fun c1_turn_towards_unseen_part_with_arrow() {
        val e = GuidanceEngine(); var t = scanning(e)
        t = drive(e, t, 14_000) { goodSignal(it) }.first
        val (_, o) = drive(e, t, 2_000) { goodSignal(it).copy(unseenArcDeg = 120f, unseenArcYawDeg = -100f) }
        assertEquals(GuidanceRuleId.C1, o.state.ruleId)
        assertEquals(Direction.LEFT, o.state.direction)
    }

    @Test fun c2_look_up_after_thirty_seconds() {
        val e = GuidanceEngine(); var t = scanning(e)
        val f = { tt: Long -> goodSignal(tt).copy(lookedUp = false) }
        val (t2, o1) = drive(e, t, 25_000, f)
        assertFalse(o1.state.ruleId == GuidanceRuleId.C2)
        val (_, o2) = drive(e, t2, 6_000, f)
        assertEquals(GuidanceRuleId.C2, o2.state.ruleId)
        assertEquals(Direction.UP, o2.state.direction)
    }

    @Test fun c3_needs_more_views_of_what_is_in_view() {
        val e = GuidanceEngine(); var t = scanning(e)
        t = drive(e, t, 14_000) { goodSignal(it) }.first
        val f = { tt: Long -> goodSignal(tt).copy(viewVoxelCount = 40, viewSingleAngleFraction = 0.9f) }
        val (t2, o1) = drive(e, t, 2_000, f)
        assertFalse(o1.state.ruleId == GuidanceRuleId.C3)
        assertEquals(GuidanceRuleId.C3, drive(e, t2, 2_000, f).second.state.ruleId)
    }

    @Test fun c4_missing_corner_with_arrow() {
        val e = GuidanceEngine(); var t = scanning(e)
        // user is back near the start (loop closed), so C5 does not outrank C4
        val near = { tt: Long -> goodSignal(tt).copy(distanceFromStartM = 0.4f) }
        t = drive(e, t, 44_000, near).first
        val (_, o) = drive(e, t, 2_000) { near(it).copy(coverageObserved = 400, coverageCovered = 300, missingCornerYawDeg = 70f) }
        assertEquals(GuidanceRuleId.C4, o.state.ruleId)
        assertEquals(Direction.RIGHT, o.state.direction)
    }

    @Test fun c5_return_to_start_then_loop_closes_and_c6_follows() {
        val th = Thresholds(stopMinScanMs = 20_000, stopMinPathM = 4f, stopMinKeyframes = 10, stopMinExtentM = 1f, loopAfterMs = 25_000)
        val e = GuidanceEngine(th); var t = scanning(e)
        // walked 3 m away and stayed there
        val away = { tt: Long -> goodSignal(tt).copy(maxDistanceFromStartM = 3f, distanceFromStartM = 3f, pathLengthM = 8f, keyframeCount = (tt / 300).toInt()) }
        val (t2, o) = drive(e, t, 26_000, away)
        assertEquals(GuidanceRuleId.C5, o.state.ruleId)
        // came back
        val back = { tt: Long -> goodSignal(tt).copy(maxDistanceFromStartM = 3f, distanceFromStartM = 0.5f, pathLengthM = 11f, keyframeCount = (tt / 300).toInt()) }
        val (t3, o2) = drive(e, t2, 3_000, back)
        assertTrue(e.summary(t3).loopClosed)
        assertEquals(GuidanceRuleId.C6, o2.state.ruleId)
    }

    @Test fun t7_redo_after_a_poor_stretch_once() {
        val e = GuidanceEngine(); var t = scanning(e)
        t = drive(e, t, 6_000) { goodSignal(it).copy(brightness = 15f) }.first // 6 s dark (L1)
        val ids = shows(e, t, 8_000) { goodSignal(it) }                       // light comes back
        assertEquals(1, ids.count { it == GuidanceRuleId.T7 })
    }

    @Test fun stop_needs_real_coverage_once_measured() {
        val th = Thresholds(stopMinScanMs = 5_000, stopMinPathM = 1f, stopMinKeyframes = 5, stopMinExtentM = 0.5f)
        val e = GuidanceEngine(th); val t = scanning(e)
        val (t2, low) = drive(e, t, 6_000) { goodSignal(it).copy(coverageObserved = 500, coverageCovered = 100) }
        assertFalse(low.state.canStop) // 20 % seen from two angles is not enough
        val (_, ok) = drive(e, t2, 1_000) { goodSignal(it).copy(coverageObserved = 500, coverageCovered = 350) }
        assertTrue(ok.state.canStop)
        assertEquals(0.7f, ok.state.coverage.surfacesSeenFromTwoPlusAngles, 1e-4f)
    }

    @Test fun every_catalogue_rule_is_implemented() {
        val implemented = lk.transitionx.capture.guidance.Rules.all(Thresholds()).map { it.id }.toSet() +
            setOf(GuidanceRuleId.T1, GuidanceRuleId.T2) // state-defined
        assertEquals(GuidanceRuleId.values().toSet(), implemented)
    }
}
