// SPDX-License-Identifier: Apache-2.0
// guidance: the decision engine (TDD v1.0 section 10). SignalFrame in, GuidanceState out.
// Contains no platform types and never reads a clock: the current time is a parameter, which is
// what makes it deterministic (NFR-13), testable without waiting, and replayable faster than
// real time for calibration (section 19).
package lk.transitionx.capture.guidance

import lk.transitionx.capture.core.CoverageState
import lk.transitionx.capture.core.GuidanceRuleId
import lk.transitionx.capture.core.GuidanceState
import lk.transitionx.capture.core.SessionState
import lk.transitionx.capture.core.Severity
import lk.transitionx.capture.core.SignalFrame

enum class AlertType { SHOWN, DISMISSED, SUPPRESSED, STATE }

/** One line of alerts.jsonl. Suppressed rules are logged too (TDD figure 5). */
data class AlertEvent(
    val atMs: Long,
    val type: AlertType,
    val ruleId: GuidanceRuleId? = null,
    val severity: Severity? = null,
    val escalated: Boolean = false,
    val reason: String? = null,
    val shownForMs: Long? = null,
    val fromState: SessionState? = null,
    val toState: SessionState? = null
)

data class EngineOutput(
    val state: GuidanceState,
    /** True when the selected rule, severity, escalation, state or canStop changed. */
    val changed: Boolean,
    val events: List<AlertEvent>
)

data class EngineSummary(
    val durationMs: Long,
    val finalState: SessionState,
    val trackingLostCount: Int,
    val trackingLostTotalMs: Long,
    val canStop: Boolean,
    /** Number of times each rule was shown to the user. */
    val shownByRule: Map<GuidanceRuleId, Int>,
    /** Number of times each rule became active but was not shown. */
    val suppressedByRule: Map<GuidanceRuleId, Int>
)

class GuidanceEngine(private val t: Thresholds = Thresholds()) {
    private class RuleState(var active: Boolean = false, var consecutive: Int = 0)

    private val rules = Rules.phase1(t)
    private val ruleStates = rules.associate { it.id to RuleState() }
    private val machine = SessionStateMachine(t)

    private var startMs = 0L
    private var lastNowMs = 0L
    private var settleUntilMs = 0L
    private var suspendedUntilMs = 0L
    private var lastKeyframeCount = 0
    private var lastKeyframeAtMs = 0L
    private var canStop = false

    private var shown: GuidanceRuleId? = null
    private var shownSinceMs = 0L
    private var escalated = false
    private val showHistory = HashMap<GuidanceRuleId, ArrayDeque<Long>>()
    private val shownCount = HashMap<GuidanceRuleId, Int>()
    private val suppressedCount = HashMap<GuidanceRuleId, Int>()
    private var lastState: GuidanceState? = null

    val sessionState: SessionState get() = machine.state

    fun start(nowMs: Long): List<AlertEvent> {
        machine.reset()
        ruleStates.values.forEach { it.active = false; it.consecutive = 0 }
        showHistory.clear(); shownCount.clear(); suppressedCount.clear()
        shown = null; escalated = false; canStop = false
        startMs = nowMs; lastNowMs = nowMs
        lastKeyframeCount = 0; lastKeyframeAtMs = nowMs
        settleUntilMs = 0; suspendedUntilMs = 0
        lastState = null
        val tr = machine.start(nowMs) ?: return emptyList()
        return listOf(AlertEvent(nowMs, AlertType.STATE, fromState = tr.from, toState = tr.to))
    }

    /** Suppresses non-blocking guidance until [untilMs], e.g. across recording transitions. */
    fun suspendUntil(untilMs: Long) {
        suspendedUntilMs = maxOf(suspendedUntilMs, untilMs)
    }

    fun stop(nowMs: Long): List<AlertEvent> {
        val events = ArrayList<AlertEvent>()
        dismissShown(nowMs, events, "stopped")
        machine.stop(nowMs)?.let { events += AlertEvent(nowMs, AlertType.STATE, fromState = it.from, toState = it.to) }
        lastNowMs = nowMs
        return events
    }

    fun evaluate(s: SignalFrame, nowMs: Long): EngineOutput {
        lastNowMs = nowMs
        val events = ArrayList<AlertEvent>()
        val state0 = machine.state
        if (state0 == SessionState.IDLE || state0 == SessionState.REVIEWING) {
            return output(nowMs, events)
        }

        machine.update(s.trackingOk, nowMs)?.let { tr ->
            events += AlertEvent(nowMs, AlertType.STATE, fromState = tr.from, toState = tr.to)
            if (tr.to == SessionState.SCANNING) {
                settleUntilMs = nowMs + t.settleMs
                lastKeyframeAtMs = nowMs // standing still while tracking was lost is not the user's fault
                lastKeyframeCount = s.keyframeCount
            }
        }
        val state = machine.state

        if (s.keyframeCount != lastKeyframeCount) {
            lastKeyframeCount = s.keyframeCount
            lastKeyframeAtMs = nowMs
        }

        if (!canStop && state == SessionState.SCANNING) {
            canStop = nowMs - startMs >= t.stopMinScanMs &&
                s.pathLengthM >= t.stopMinPathM &&
                s.keyframeCount >= t.stopMinKeyframes &&
                s.maxDistanceFromStartM >= t.stopMinExtentM
        }

        val permitted = PermittedRules.of(state)
        val ctx = RuleContext(nowMs, lastKeyframeAtMs, canStop)

        // 1. Hysteresis and debounce for every signal rule permitted in this state.
        val active = ArrayList<GuidanceRuleId>()
        val newlyActive = ArrayList<GuidanceRuleId>()
        for (rule in rules) {
            val st = ruleStates.getValue(rule.id)
            if (rule.id !in permitted) {
                st.active = false; st.consecutive = 0
                continue
            }
            val raw = if (st.active) rule.stay(s, ctx) else rule.enter(s, ctx)
            st.consecutive = if (raw) st.consecutive + 1 else 0
            val wasActive = st.active
            st.active = if (wasActive) raw else raw && (!rule.debounced || st.consecutive >= t.debounceFrames)
            if (st.active) active += rule.id
            if (st.active && !wasActive) newlyActive += rule.id
        }
        // State-defined rules.
        if (state == SessionState.INITIALIZING) active += GuidanceRuleId.T2
        if (state == SessionState.TRACKING_LOST) active += GuidanceRuleId.T1

        // 2. Candidates in priority order (tier, then catalogue order).
        val quiet = nowMs < settleUntilMs || nowMs < suspendedUntilMs
        val ordered = active.sortedWith(compareBy({ it.tier }, { it.ordinal }))
        val candidates = ArrayList<GuidanceRuleId>()
        for (id in ordered) {
            if (quiet && id.tier > 0) continue
            if (id.tier > 0 && id != shown && showsInWindow(id, nowMs) >= t.cooldownMaxShows) continue
            candidates += id
        }
        val best = candidates.firstOrNull()

        // 3. Selection with minimum display time. Tier 0 and state changes pre-empt.
        val current = shown
        // A message that is still true is kept unless something strictly more urgent appears:
        // same-tier rules do not take turns (real capture: S1/L3 alternated every 1.5 s).
        val currentActive = current != null && current in active && !(quiet && current.tier > 0)
        val next: GuidanceRuleId? = when {
            current == null -> best
            current !in permitted -> best
            best == current -> current
            nowMs - shownSinceMs < t.minDisplayMs && (best == null || best.tier > 0) -> current
            currentActive && (best == null || best.tier >= current.tier) -> current
            else -> best
        }
        if (next != current) {
            dismissShown(nowMs, events, if (next == null) "cleared" else "replaced")
            if (next != null) show(next, nowMs, events)
        }

        // 4. Log rules that became active this frame but are not what the user sees.
        for (id in newlyActive) {
            if (id == shown) continue
            run {
                val reason = when {
                    quiet && id.tier > 0 -> "settling"
                    id.tier > 0 && showsInWindow(id, nowMs) >= t.cooldownMaxShows -> "cooldown"
                    else -> "lower_priority"
                }
                suppressedCount[id] = (suppressedCount[id] ?: 0) + 1
                events += AlertEvent(nowMs, AlertType.SUPPRESSED, id, id.severity, reason = reason)
            }
        }
        return output(nowMs, events)
    }

    fun currentState(nowMs: Long = lastNowMs): GuidanceState = buildState(nowMs)

    fun summary(nowMs: Long = lastNowMs): EngineSummary = EngineSummary(
        durationMs = nowMs - startMs,
        finalState = machine.state,
        trackingLostCount = machine.trackingLostCount,
        trackingLostTotalMs = machine.trackingLostTotalMs +
            if (machine.state == SessionState.TRACKING_LOST) nowMs - machine.enteredAtMs else 0,
        canStop = canStop,
        shownByRule = shownCount.toMap(),
        suppressedByRule = suppressedCount.toMap()
    )

    private fun showsInWindow(id: GuidanceRuleId, nowMs: Long): Int {
        val h = showHistory[id] ?: return 0
        while (h.isNotEmpty() && nowMs - h.first() > t.cooldownWindowMs) h.removeFirst()
        return h.size
    }

    private fun show(id: GuidanceRuleId, nowMs: Long, events: MutableList<AlertEvent>) {
        val h = showHistory.getOrPut(id) { ArrayDeque() }
        showsInWindow(id, nowMs)
        h.addLast(nowMs)
        escalated = id.tier > 0 && h.size >= t.cooldownMaxShows
        shown = id
        shownSinceMs = nowMs
        shownCount[id] = (shownCount[id] ?: 0) + 1
        events += AlertEvent(nowMs, AlertType.SHOWN, id, id.severity, escalated = escalated)
    }

    private fun dismissShown(nowMs: Long, events: MutableList<AlertEvent>, reason: String) {
        val id = shown ?: return
        events += AlertEvent(nowMs, AlertType.DISMISSED, id, id.severity, escalated = escalated,
            reason = reason, shownForMs = nowMs - shownSinceMs)
        shown = null
        escalated = false
    }

    private fun buildState(nowMs: Long): GuidanceState {
        val id = shown
        return GuidanceState(
            ruleId = id,
            severity = id?.severity ?: Severity.INFO,
            sessionState = machine.state,
            coverage = CoverageState.POSE_TRAIL_ONLY,
            canStop = canStop,
            escalated = escalated,
            elapsedMs = if (machine.state == SessionState.IDLE) 0 else nowMs - startMs
        )
    }

    private fun output(nowMs: Long, events: List<AlertEvent>): EngineOutput {
        val st = buildState(nowMs)
        val prev = lastState
        val changed = prev == null || prev.ruleId != st.ruleId || prev.severity != st.severity ||
            prev.escalated != st.escalated || prev.sessionState != st.sessionState || prev.canStop != st.canStop
        lastState = st
        return EngineOutput(st, changed, events)
    }
}
