// SPDX-License-Identifier: Apache-2.0
// replay: offline harness (TDD v1.0 section 19). Runs the real decision engine over a recorded
// quality.jsonl, faster than real time, with any threshold set. No device needed.
package lk.transitionx.capture.replay

import java.io.File
import lk.transitionx.capture.core.GuidanceRuleId
import lk.transitionx.capture.core.SignalFrame
import lk.transitionx.capture.guidance.AlertEvent
import lk.transitionx.capture.guidance.EngineSummary
import lk.transitionx.capture.guidance.GuidanceEngine
import lk.transitionx.capture.guidance.Thresholds

object ReplayRunner {
    data class Result(
        /** (time ms, rule shown from that time; null = banner hidden) */
        val timeline: List<Pair<Long, GuidanceRuleId?>>,
        val alerts: List<AlertEvent>,
        val summary: EngineSummary
    )

    fun run(frames: List<SignalFrame>, thresholds: Thresholds, sessionStartMs: Long? = null): Result {
        val engine = GuidanceEngine(thresholds)
        val alerts = ArrayList<AlertEvent>()
        val timeline = ArrayList<Pair<Long, GuidanceRuleId?>>()
        if (frames.isEmpty()) {
            alerts += engine.start(0)
            return Result(timeline, alerts, engine.summary(0))
        }
        val t0 = sessionStartMs ?: (frames.first().timestampNs / 1_000_000L)
        alerts += engine.start(t0)
        var lastRule: GuidanceRuleId? = null
        var first = true
        for (s in frames) {
            val now = s.timestampNs / 1_000_000L
            val out = engine.evaluate(s, now)
            alerts += out.events
            if (first || out.state.ruleId != lastRule) {
                timeline += now to out.state.ruleId
                lastRule = out.state.ruleId
                first = false
            }
        }
        val end = frames.last().timestampNs / 1_000_000L
        alerts += engine.stop(end)
        return Result(timeline, alerts, engine.summary(end))
    }

    fun readQuality(file: File): List<SignalFrame> =
        file.readLines().filter { it.isNotBlank() }.map { LogCodec.signalFromLine(it) }

    /**
     * Threshold sweep: replays the same frames once per value and reports how often each rule was
     * shown. With pass/fail labels from Rysera this becomes the precision/recall table.
     */
    fun sweep(
        frames: List<SignalFrame>,
        base: Thresholds,
        values: List<Float>,
        apply: (Thresholds, Float) -> Thresholds
    ): Map<Float, Map<GuidanceRuleId, Int>> =
        values.associateWith { v -> run(frames, apply(base, v)).summary.shownByRule }
}

/**
 * Command line: java -cp <classes> lk.transitionx.capture.replay.ReplayMainKt quality.jsonl [thresholds.json]
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("usage: replay <quality.jsonl> [thresholds.json]")
        return
    }
    val frames = ReplayRunner.readQuality(File(args[0]))
    val thresholds = if (args.size > 1) Thresholds.fromMap(JsonLite.parseObject(File(args[1]).readText())) else Thresholds()
    val r = ReplayRunner.run(frames, thresholds)
    println("frames=${frames.size} thresholds=${thresholds.version}")
    for ((t, rule) in r.timeline) println("%10d  %s".format(t - r.timeline.first().first, rule?.name ?: "-"))
    println("shown: ${r.summary.shownByRule}")
    println("suppressed: ${r.summary.suppressedByRule}")
    println("trackingLost=${r.summary.trackingLostCount} canStop=${r.summary.canStop}")
}
