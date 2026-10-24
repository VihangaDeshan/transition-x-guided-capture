// SPDX-License-Identifier: Apache-2.0
// guidance: session state machine (TDD v1.0 section 10.3). The permitted message set is a property
// of the state, held in one table, so a rule added later cannot forget to check the state.
package lk.transitionx.capture.guidance

import lk.transitionx.capture.core.GuidanceRuleId
import lk.transitionx.capture.core.GuidanceRuleId.*
import lk.transitionx.capture.core.SessionState

object PermittedRules {
    private val blocking = setOf(T4, T5)

    val table: Map<SessionState, Set<GuidanceRuleId>> = mapOf(
        SessionState.IDLE to emptySet(),
        SessionState.INITIALIZING to setOf(T2) + blocking,
        SessionState.SCANNING to setOf(G4, G1, L1, L3, S1, G2, L2, T6, T3, G3, C6) + blocking,
        SessionState.TRACKING_LOST to setOf(T1) + blocking,
        SessionState.REVIEWING to emptySet()
    )

    fun of(state: SessionState): Set<GuidanceRuleId> = table.getValue(state)
}

data class StateTransition(val atMs: Long, val from: SessionState, val to: SessionState)

/**
 * INITIALIZING -> SCANNING requires tracking to be stable for [Thresholds.trackingStableMs].
 * SCANNING -> TRACKING_LOST requires tracking to be lost for [Thresholds.trackingLostGraceMs],
 * so a single dropped frame does not wipe the banner. TRACKING_LOST -> SCANNING again requires
 * stable tracking. stop() moves to REVIEWING from anywhere.
 */
class SessionStateMachine(private val t: Thresholds) {
    var state: SessionState = SessionState.IDLE
        private set
    var enteredAtMs: Long = 0
        private set
    private var stableSinceMs: Long? = null
    private var lostSinceMs: Long? = null

    var trackingLostCount = 0
        private set
    var trackingLostTotalMs = 0L
        private set

    fun start(nowMs: Long): StateTransition? = moveTo(SessionState.INITIALIZING, nowMs).also {
        trackingLostCount = 0
        trackingLostTotalMs = 0
        stableSinceMs = null
        lostSinceMs = null
    }

    fun stop(nowMs: Long): StateTransition? = moveTo(SessionState.REVIEWING, nowMs)

    fun reset() {
        state = SessionState.IDLE
        enteredAtMs = 0
        stableSinceMs = null
        lostSinceMs = null
        trackingLostCount = 0
        trackingLostTotalMs = 0
    }

    /** Feeds the current tracking condition; returns a transition when the state changed. */
    fun update(trackingOk: Boolean, nowMs: Long): StateTransition? {
        when (state) {
            SessionState.INITIALIZING, SessionState.TRACKING_LOST -> {
                if (trackingOk) {
                    val since = stableSinceMs ?: nowMs.also { stableSinceMs = it }
                    if (nowMs - since >= t.trackingStableMs) return moveTo(SessionState.SCANNING, nowMs)
                } else {
                    stableSinceMs = null
                }
            }
            SessionState.SCANNING -> {
                if (!trackingOk) {
                    val since = lostSinceMs ?: nowMs.also { lostSinceMs = it }
                    if (nowMs - since >= t.trackingLostGraceMs) {
                        trackingLostCount++
                        return moveTo(SessionState.TRACKING_LOST, nowMs)
                    }
                } else {
                    lostSinceMs = null
                }
            }
            SessionState.IDLE, SessionState.REVIEWING -> Unit
        }
        return null
    }

    private fun moveTo(next: SessionState, nowMs: Long): StateTransition? {
        if (next == state) return null
        val from = state
        if (from == SessionState.TRACKING_LOST) trackingLostTotalMs += nowMs - enteredAtMs
        state = next
        enteredAtMs = nowMs
        stableSinceMs = null
        lostSinceMs = null
        return StateTransition(nowMs, from, next)
    }
}
