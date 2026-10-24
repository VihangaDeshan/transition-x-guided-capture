// SPDX-License-Identifier: Apache-2.0
package lk.transitionx.capture

import java.io.File
import lk.transitionx.capture.core.CoverageState
import lk.transitionx.capture.core.GuidanceRuleId
import lk.transitionx.capture.core.TrackingFailure
import lk.transitionx.capture.core.TrackingStatus
import lk.transitionx.capture.export.ExportDocs
import lk.transitionx.capture.guidance.AlertEvent
import lk.transitionx.capture.guidance.AlertType
import lk.transitionx.capture.guidance.GuidanceEngine
import lk.transitionx.capture.guidance.Thresholds
import lk.transitionx.capture.replay.JsonLite
import lk.transitionx.capture.replay.LogCodec
import lk.transitionx.capture.replay.ReplayRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayAndExportTest {
    @Test fun json_round_trip() {
        val v = mapOf("a" to 1, "b" to listOf(1.5, "x\"y\n", null, true), "c" to mapOf("d" to -2.25e-3))
        val parsed = JsonLite.parse(JsonLite.write(v)) as Map<*, *>
        assertEquals(1.0, parsed["a"])
        assertEquals(listOf(1.5, "x\"y\n", null, true), parsed["b"])
        assertEquals(-0.00225, (parsed["c"] as Map<*, *>)["d"] as Double, 1e-9)
        assertEquals("\"\\u0001\"", JsonLite.quote("\u0001"))
        assertEquals("NaN-safe", JsonLite.write(Float.NaN).let { if (it == "null") "NaN-safe" else it })
    }

    @Test fun signal_line_round_trip() {
        val s = goodSignal(12_345, keyframes = 7).copy(failureReason = TrackingFailure.INSUFFICIENT_LIGHT, distanceToSurface = 1.25f)
        val back = LogCodec.signalFromLine(LogCodec.signalLine(s))
        assertEquals(s.timestampNs, back.timestampNs)
        assertEquals(s.failureReason, back.failureReason)
        assertEquals(s.keyframeCount, back.keyframeCount)
        assertEquals(1.25f, back.distanceToSurface!!, 1e-5f)
        assertEquals(s.blurScore, back.blurScore, 1e-3f)
    }

    @Test fun alert_line_round_trip() {
        val e = AlertEvent(99, AlertType.DISMISSED, GuidanceRuleId.G4, GuidanceRuleId.G4.severity, true, "replaced", 1600)
        assertEquals(e, LogCodec.alertFromLine(LogCodec.alertLine(e)))
    }

    @Test fun pose_line_has_all_fields() {
        val m = JsonLite.parseObject(LogCodec.poseLine(5, poseAt(1f, 2f, 90f), TrackingStatus.TRACKING, TrackingFailure.NONE))
        for (k in listOf("t", "tx", "ty", "tz", "qx", "qy", "qz", "qw", "tracking", "failure")) assertTrue(k, m.containsKey(k))
    }

    /** Replay through a quality.jsonl file reproduces the live engine exactly (same code path). */
    @Test fun replay_matches_live_engine() {
        val frames = (0 until 600).map { i ->
            val t = i * 33L
            goodSignal(t, keyframes = i / 20).copy(
                blurScore = if (i in 150..220) 30f else 300f,
                windowRotationDeg = if (i in 300..360) 80f else 5f,
                windowTranslationM = if (i in 300..360) 0.01f else 0.9f
            )
        }
        val file = File.createTempFile("quality", ".jsonl")
        file.writeText(frames.joinToString("\n") { LogCodec.signalLine(it) })
        val replayed = ReplayRunner.run(ReplayRunner.readQuality(file), Thresholds())

        val live = GuidanceEngine()
        live.start(0)
        val liveShown = ArrayList<GuidanceRuleId>()
        var prev: GuidanceRuleId? = null
        for (s in frames) {
            val r = live.evaluate(s, s.timestampNs / 1_000_000).state.ruleId
            if (r != null && r != prev) liveShown += r
            prev = r
        }
        val replayShown = replayed.timeline.mapNotNull { it.second }
        assertEquals(liveShown, replayShown)
        assertTrue(GuidanceRuleId.L3 in replayShown)
        assertTrue(GuidanceRuleId.G4 in replayShown)
        file.delete()
    }

    @Test fun sweep_changes_warning_counts() {
        val frames = (0 until 400).map { i -> goodSignal(i * 33L, keyframes = i / 20).copy(blurScore = 120f) }
        val res = ReplayRunner.sweep(frames, Thresholds(), listOf(100f, 130f)) { t, v -> t.copy(blurEnter = v, blurExit = v + 50) }
        assertNull(res.getValue(100f)[GuidanceRuleId.L3])
        assertEquals(1, res.getValue(130f)[GuidanceRuleId.L3])
    }

    @Test fun thresholds_from_json_override_only_given_keys() {
        val text = listOf("config", "../../config", "../../../../../config")
            .map { File(it, "thresholds.v1.json") }.firstOrNull { it.exists() }?.readText()
            ?: """{"version":"test","blurEnter":80,"debounceFrames":2}"""
        val t = Thresholds.fromMap(JsonLite.parseObject(text))
        assertTrue(t.version.isNotEmpty())
        val custom = Thresholds.fromMap(mapOf("blurEnter" to 80.0, "debounceFrames" to 2.0))
        assertEquals(80f, custom.blurEnter, 0f)
        assertEquals(2, custom.debounceFrames)
        assertEquals(Thresholds().blurExit, custom.blurExit, 0f)
    }

    @Test fun manifest_and_summary_documents() {
        val engine = GuidanceEngine()
        engine.start(0)
        val session = ExportDocs.SessionInfo("abc", "2026-10-24T03:00:00Z", 60_000, 1800, 900, 450, 0,
            "ARCORE_RECORDING_API", true, null, "session.mp4", "USER_STOP")
        val device = ExportDocs.DeviceInfo("samsung", "SM-A556E", "14", 34, "1.50")
        val cam = ExportDocs.CameraInfo(640, 480, 1920, 1080, 30, 500f, 500f, 320f, 240f)
        val manifest = JsonLite.parseObject(JsonLite.write(
            ExportDocs.manifest(session, device, cam, CoverageState.POSE_TRAIL_ONLY, "1.0.0", "1.0-initial")))
        assertEquals("1.0", manifest["formatVersion"])
        assertEquals("POSE_TRAIL", (manifest["coverage"] as Map<*, *>)["method"])
        assertEquals(500.0, (manifest["camera"] as Map<*, *>)["fx"])
        assertEquals("1.0-initial", (manifest["app"] as Map<*, *>)["thresholdsVersion"])

        val trail = ExportDocs.TrailInfo(8f, 50, 3f, listOf(floatArrayOf(0f, 0f), floatArrayOf(1f, 2f)))
        val cov = JsonLite.parseObject(JsonLite.write(ExportDocs.coverage(CoverageState.POSE_TRAIL_ONLY, trail)))
        assertNull(cov["surfacesSeenFromTwoPlusAngles"]) // no percentage claimed for POSE_TRAIL
        val sum = ExportDocs.summary(engine.summary(60_000), session, trail, CoverageState.POSE_TRAIL_ONLY)
        assertEquals("CHECK", sum["verdict"])
        assertEquals(ExportDocs.CaptureVerdict.INCOMPLETE,
            ExportDocs.verdict(engine.summary(60_000), session.copy(recordingOk = false)))
    }
}
