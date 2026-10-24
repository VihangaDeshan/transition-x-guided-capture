// SPDX-License-Identifier: Apache-2.0
package lk.transitionx.capture

import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import lk.transitionx.capture.core.GuidanceRuleId
import lk.transitionx.capture.export.PackageIntegrity
import lk.transitionx.capture.guidance.AlertType
import lk.transitionx.capture.guidance.Thresholds
import lk.transitionx.capture.replay.LogCodec
import lk.transitionx.capture.replay.ReplayRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test on the first real capture: Galaxy A55, Android 16, 83 s, 2504 frames
 * (scan_77001a7f, 2026-10-24). textureFraction/sharpness were recomputed with ImageMetrics from
 * the recorded 640x480 frames (the same stream the phone analysed). See docs/analysis/.
 */
class RealCaptureTest {
    private val frames by lazy {
        val stream = javaClass.getResourceAsStream("/fixtures/a55-scan1-quality.jsonl.gz")
            ?: error("fixture missing from test resources")
        GZIPInputStream(stream).bufferedReader().readLines().filter { it.isNotBlank() }.map { LogCodec.signalFromLine(it) }
    }
    private val thresholds = Thresholds(version = "test")

    @Test fun fixture_is_the_full_session() {
        assertEquals(2504, frames.size)
        val seconds = (frames.last().timestampNs - frames.first().timestampNs) / 1e9
        assertEquals(83.5, seconds, 0.5)
        assertTrue(frames.all { it.trackingOk })
    }

    @Test fun no_blank_wall_warnings_in_a_furnished_room() {
        // v1.0 showed S1 ten times (once for 15.5 s) because the ARCore point count was 0-11.
        val r = ReplayRunner.run(frames, thresholds)
        assertEquals(null, r.summary.shownByRule[GuidanceRuleId.S1])
    }

    @Test fun real_problems_are_still_reported() {
        val r = ReplayRunner.run(frames, thresholds)
        val shown = r.summary.shownByRule
        assertTrue("dark spell 33-45 s", (shown[GuidanceRuleId.L1] ?: 0) >= 1)
        assertTrue("spinning in place", (shown[GuidanceRuleId.G4] ?: 0) >= 1)
        assertTrue("fast turn at 31 s", (shown[GuidanceRuleId.G1] ?: 0) >= 1)
        assertTrue("motion blur at 32 s and 72 s", (shown[GuidanceRuleId.L3] ?: 0) >= 1)
        assertTrue("mostly standing still", (shown[GuidanceRuleId.G3] ?: 0) >= 1)
    }

    @Test fun same_tier_messages_do_not_take_turns() {
        // v1.0: S1 and L3 (both tier 2) replaced each other every ~1.5 s.
        val shows = ReplayRunner.run(frames, thresholds).alerts.filter { it.type == AlertType.SHOWN }
        var pingPong = 0
        for (i in 2 until shows.size) {
            val a = shows[i - 2]; val b = shows[i - 1]; val c = shows[i]
            if (a.ruleId == c.ruleId && a.ruleId != b.ruleId && a.ruleId!!.tier == b.ruleId!!.tier &&
                c.atMs - a.atMs < 5_000) pingPong++
        }
        assertEquals(0, pingPong)
    }

    @Test fun message_rate_is_reasonable() {
        val r = ReplayRunner.run(frames, thresholds)
        val total = r.summary.shownByRule.values.sum()
        val perMinute = total / (83.5 / 60)
        assertTrue("shown per minute = $perMinute", perMinute < 18) // v1.0: 25 shows = 18/min
    }

    @Test fun deterministic_on_real_data() {
        assertEquals(ReplayRunner.run(frames, thresholds).timeline, ReplayRunner.run(frames, thresholds).timeline)
    }
}

class IntegrityAndValidationTest {
    @Test fun zip_contains_every_file_and_checksums_verify() {
        val dir = kotlin.io.path.createTempDirectory("scan_").toFile()
        File(dir, "session.mp4").writeBytes(ByteArray(300_000) { (it % 251).toByte() })
        File(dir, "poses.jsonl").writeText("{\"t\":1}\n{\"t\":2}\n")
        File(dir, "manifest.json").writeText("{}")
        val zip = File(kotlin.io.path.createTempDirectory("exp").toFile(), "scan_x.zip")
        val r = PackageIntegrity.zipWithChecksums(dir, zip, "scan_x")
        assertEquals(setOf("session.mp4", "poses.jsonl", "manifest.json"), r.digests.keys)
        assertFalse(File(zip.path + ".part").exists())
        ZipFile(zip).use { z ->
            val names = z.entries().toList().map { it.name }.toSet()
            assertEquals(setOf("scan_x/session.mp4", "scan_x/poses.jsonl", "scan_x/manifest.json", "scan_x/checksums.sha256"), names)
            val sums = PackageIntegrity.parseChecksums(z.getInputStream(z.getEntry("scan_x/checksums.sha256")).bufferedReader().readText())
            for ((name, hex) in sums) {
                assertEquals(name, hex, z.getInputStream(z.getEntry("scan_x/$name")).use { PackageIntegrity.sha256Hex(it) })
                assertEquals(hex, PackageIntegrity.sha256Hex(File(dir, name)))
            }
        }
        // a tampered file no longer matches
        File(dir, "poses.jsonl").appendText("x")
        assertFalse(PackageIntegrity.sha256Hex(File(dir, "poses.jsonl")) == r.digests["poses.jsonl"])
    }

    @Test fun known_sha256() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            PackageIntegrity.sha256Hex("abc".byteInputStream()))
    }

    @Test fun unsafe_archive_root_is_rejected() {
        val dir = kotlin.io.path.createTempDirectory("scan_").toFile()
        try {
            PackageIntegrity.zipWithChecksums(dir, File(dir, "a.zip"), "../evil")
            throw AssertionError("expected rejection")
        } catch (e: IllegalArgumentException) { /* expected */ }
    }

    @Test fun hostile_thresholds_are_clamped() {
        val t = Thresholds.fromMap(mapOf(
            "version" to "../../etc\"; rm", "debounceFrames" to -5.0, "minDisplayMs" to 1e12,
            "blurEnter" to 200.0, "blurExit" to 50.0, "textureEnter" to 7.0, "maxSessionMs" to 1.0,
            "imageSampleInterval" to 0.0))
        assertTrue(t.version.all { it.isLetterOrDigit() || it in ".-_" })
        assertEquals(1, t.debounceFrames)
        assertEquals(10_000L, t.minDisplayMs)
        assertTrue(t.blurExit >= t.blurEnter)
        assertEquals(1f, t.textureEnter, 0f)
        assertEquals(30_000L, t.maxSessionMs)
        assertEquals(1, t.imageSampleInterval)
    }

    @Test fun default_thresholds_survive_validation_unchanged() {
        assertEquals(Thresholds(), Thresholds.validated(Thresholds()))
    }
}
