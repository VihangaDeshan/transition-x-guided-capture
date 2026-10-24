// SPDX-License-Identifier: Apache-2.0
package lk.transitionx.capture

import lk.transitionx.capture.core.CaptureFrame
import lk.transitionx.capture.core.CapturePose
import lk.transitionx.capture.core.TrackingFailure
import lk.transitionx.capture.core.TrackingStatus
import lk.transitionx.capture.signals.ImageMetrics
import lk.transitionx.capture.signals.MotionConfig
import lk.transitionx.capture.signals.MotionTracker
import lk.transitionx.capture.signals.SignalAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalsTest {
    private fun checkerboard(w: Int, h: Int, cell: Int): ByteArray =
        ByteArray(w * h) { i -> if (((i % w) / cell + (i / w) / cell) % 2 == 0) 20 else 230.toByte() }

    private fun boxBlur(src: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0; var n = 0
            for (dy in -r..r) for (dx in -r..r) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) { s += src[yy * w + xx].toInt() and 0xFF; n++ }
            }
            out[y * w + x] = (s / n).toByte()
        }
        return out
    }

    @Test fun pose_math() {
        val a = CapturePose.IDENTITY
        assertEquals(0f, a.angleDegreesTo(a), 1e-3f)
        assertEquals(90f, a.angleDegreesTo(poseAt(0f, 0f, 90f)), 0.01f)
        assertEquals(5f, a.translationDistanceTo(CapturePose(3f, 4f, 0f, 0f, 0f, 0f, 1f)), 1e-5f)
        // q and -q are the same rotation
        val q = poseAt(0f, 0f, 40f)
        val neg = q.copy(qx = -q.qx, qy = -q.qy, qz = -q.qz, qw = -q.qw)
        assertEquals(0f, q.angleDegreesTo(neg), 0.01f)
    }

    @Test fun sharp_image_scores_higher_than_blurred() {
        val w = 640; val h = 480
        val sharp = checkerboard(w, h, 16)
        val blurred = boxBlur(sharp, w, h, 6)
        val s = ImageMetrics.analyze(sharp, w, h)
        val b = ImageMetrics.analyze(blurred, w, h)
        assertTrue("sharp=${s.blurScore} blurred=${b.blurScore}", s.blurScore > 4 * b.blurScore)
        // more blur, lower score
        val heavier = ImageMetrics.analyze(boxBlur(sharp, w, h, 10), w, h)
        assertTrue(heavier.blurScore < b.blurScore)
    }

    @Test fun flat_image_has_zero_variance_and_correct_brightness() {
        val st = ImageMetrics.analyze(ByteArray(320 * 240) { 100 }, 320, 240)
        assertEquals(0f, st.blurScore, 1e-4f)
        assertEquals(100f, st.brightness, 1e-3f)
        assertEquals(0f, st.clippedHigh, 1e-4f)
    }

    @Test fun clipping_percentages() {
        val img = ByteArray(160 * 120) { i -> if (i < 160 * 120 / 4) 255.toByte() else if (i < 160 * 120 / 2) 0 else 128.toByte() }
        val st = ImageMetrics.analyzeDownsampled(img)
        assertEquals(25f, st.clippedHigh, 0.01f)
        assertEquals(25f, st.clippedLow, 0.01f)
    }

    @Test fun row_stride_padding_is_ignored() {
        val w = 320; val h = 240; val stride = 384
        val padded = ByteArray(stride * h) { i -> if (i % stride >= w) 255.toByte() else 60 }
        val st = ImageMetrics.analyze(padded, w, h, stride)
        assertEquals(60f, st.brightness, 1e-3f)
        assertEquals(0f, st.clippedHigh, 1e-4f)
    }

    @Test fun spinning_in_place_is_rotation_without_translation() {
        val m = MotionTracker()
        var last = m.update(0, poseAt(0f, 0f, 0f), true)
        // 30 fps for 2 s, 30 deg/s yaw, no translation
        for (i in 1..60) last = m.update(i * 33_333_333L, poseAt(0f, 0f, i * 1f), true)
        assertTrue("rot=${last.windowRotationDeg}", last.windowRotationDeg > 50f)
        assertTrue(last.windowTranslationM < 0.01f)
        assertEquals(30f, last.angularVelocity, 3f)
        assertEquals(0, last.keyframeCount)
    }

    @Test fun walking_creates_keyframes_and_path() {
        val m = MotionTracker(MotionConfig(keyframeBaselineM = 0.15f))
        var last = m.update(0, poseAt(0f, 0f, 0f), true)
        // walk 3 m along x at 0.5 m/s, 30 fps
        for (i in 1..180) last = m.update(i * 33_333_333L, poseAt(i * 0.5f / 30f, 0f, 0f), true)
        assertTrue("kf=${last.keyframeCount}", last.keyframeCount in 17..20)
        assertEquals(3f, last.pathLengthM, 0.2f)
        assertEquals(3f, last.maxDistanceFromStartM, 0.01f)
        assertEquals(0.5f, last.linearVelocity, 0.05f)
        assertTrue(last.windowTranslationM > 0.9f)
    }

    @Test fun tracking_loss_clears_velocity_and_history() {
        val m = MotionTracker()
        m.update(0, poseAt(0f, 0f, 0f), true)
        m.update(100_000_000, poseAt(0.1f, 0f, 20f), true)
        val lost = m.update(200_000_000, poseAt(0.1f, 0f, 20f), false)
        assertEquals(0f, lost.angularVelocity, 0f)
        // relocalisation jump must not produce a velocity spike
        val back = m.update(300_000_000, poseAt(5f, 0f, 180f), true)
        assertEquals(0f, back.angularVelocity, 0f)
        assertEquals(0f, back.linearVelocity, 0f)
    }

    @Test fun hand_shake_measures_as_jitter_but_smooth_pan_does_not() {
        val shaky = MotionTracker()
        val smooth = MotionTracker()
        var js = shaky.update(0, poseAt(0f, 0f, 0f), true)
        var jm = smooth.update(0, poseAt(0f, 0f, 0f), true)
        for (i in 1..45) {
            val t = i * 33_333_333L
            js = shaky.update(t, poseAt(0f, 0f, if (i % 2 == 0) 1.0f else -1.0f), true)
            jm = smooth.update(t, poseAt(0f, 0f, i * 0.66f), true)
        }
        assertTrue("shaky=${js.jitterDegPerSec}", js.jitterDegPerSec > 20f)
        assertTrue("smooth=${jm.jitterDegPerSec}", jm.jitterDegPerSec < 2f)
    }

    @Test fun analyzer_carries_image_forward_and_reports_age() {
        val a = SignalAnalyzer()
        val luma = ByteArray(160 * 120) { 30 }
        val f1 = CaptureFrame(1_000_000_000, poseAt(0f, 0f, 0f), TrackingStatus.TRACKING, TrackingFailure.NONE, luma, 160, 120, 160, null, 80)
        val r1 = a.process(f1, 0)
        assertTrue(r1.imageAnalysed)
        assertEquals(30f, r1.signal.brightness, 1e-3f)
        assertEquals(0L, r1.signal.imageAgeMs)
        val f2 = CaptureFrame(1_400_000_000, poseAt(0f, 0f, 0f), TrackingStatus.TRACKING, TrackingFailure.NONE, null, 0, 0, 0, null, 80)
        val r2 = a.process(f2, 0)
        assertEquals(30f, r2.signal.brightness, 1e-3f)
        assertEquals(400L, r2.signal.imageAgeMs)
        assertEquals(80, r2.signal.featureCount)
    }
}
