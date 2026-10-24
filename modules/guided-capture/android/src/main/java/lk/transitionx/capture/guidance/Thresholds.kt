// SPDX-License-Identifier: Apache-2.0
// guidance: threshold set. Values are TDD v1.0 section 19 starting points, NOT calibrated
// constants. The canonical copy lives in config/thresholds.v1.json and is passed in at runtime,
// so a calibration result is a data change, not a code change.
package lk.transitionx.capture.guidance

data class Thresholds(
    val version: String = "1.0-initial",

    // G1 turn too fast (deg/s), hysteresis enter/exit
    val angularVelocityEnter: Float = 45f,
    val angularVelocityExit: Float = 35f,

    // L3 blur (Laplacian variance on 160x120; higher is sharper)
    val blurEnter: Float = 100f,
    val blurExit: Float = 150f,

    // L1 too dark (mean luminance 0..255)
    val darkEnter: Float = 40f,
    val darkExit: Float = 50f,

    // L2 too bright (% of pixels above 250)
    val clipHighEnter: Float = 15f,
    val clipHighExit: Float = 10f,

    // S1 blank wall (ARCore feature points in view)
    val featureEnter: Int = 20,
    val featureExit: Int = 30,

    // G3 keep moving: no new keyframe (baseline >= baselineMin) for this long
    val baselineMinM: Float = 0.15f,
    val keepMovingAfterMs: Long = 3000,

    // G4 spin in place: rotation over the window with almost no translation
    val spinRotationEnterDeg: Float = 30f,
    val spinRotationExitDeg: Float = 20f,
    val spinTranslationEnterM: Float = 0.10f,
    val spinTranslationExitM: Float = 0.15f,
    val spinWindowMs: Long = 2000,

    // G2 hold steady: hand shake (deg/s) while not walking
    val jitterEnter: Float = 10f,
    val jitterExit: Float = 6f,
    val jitterMaxLinearVelocity: Float = 0.3f,

    // Reserved: linear velocity (no Phase 1 rule uses it yet)
    val linearVelocityMax: Float = 1.0f,

    // T3 thermal (PowerManager status: 2 MODERATE, 3 SEVERE)
    val thermalWarnStatus: Int = 3,

    // C6 capture sufficient to stop (pose-trail proxy until angular coverage exists)
    val stopMinScanMs: Long = 45_000,
    val stopMinPathM: Float = 6f,
    val stopMinKeyframes: Int = 30,
    val stopMinExtentM: Float = 1.5f,

    // Image measurements older than this are ignored (thermal suspension, dropped samples)
    val imageMaxAgeMs: Long = 1500,

    // Temporal stabilisation (TDD 10.4)
    val debounceFrames: Int = 3,
    val minDisplayMs: Long = 1500,
    val cooldownWindowMs: Long = 10_000,
    val cooldownMaxShows: Int = 3,
    val settleMs: Long = 1000,
    val trackingStableMs: Long = 1000,
    val trackingLostGraceMs: Long = 500,

    // Session policy (TDD 5.3)
    val maxSessionMs: Long = 600_000,
    val warnBeforeEndMs: Long = 60_000,
    /** Image analysis runs on every n-th frame (doubled on the first thermal warning). */
    val imageSampleInterval: Int = 4
) {
    companion object {
        /** Builds a set from a loosely typed map (parsed JSON); unknown keys are ignored. */
        fun fromMap(map: Map<String, Any?>): Thresholds {
            val d = Thresholds()
            fun f(k: String, def: Float): Float = (map[k] as? Number)?.toFloat() ?: def
            fun i(k: String, def: Int): Int = (map[k] as? Number)?.toInt() ?: def
            fun l(k: String, def: Long): Long = (map[k] as? Number)?.toLong() ?: def
            return Thresholds(
                version = (map["version"] as? String) ?: d.version,
                angularVelocityEnter = f("angularVelocityEnter", d.angularVelocityEnter),
                angularVelocityExit = f("angularVelocityExit", d.angularVelocityExit),
                blurEnter = f("blurEnter", d.blurEnter),
                blurExit = f("blurExit", d.blurExit),
                darkEnter = f("darkEnter", d.darkEnter),
                darkExit = f("darkExit", d.darkExit),
                clipHighEnter = f("clipHighEnter", d.clipHighEnter),
                clipHighExit = f("clipHighExit", d.clipHighExit),
                featureEnter = i("featureEnter", d.featureEnter),
                featureExit = i("featureExit", d.featureExit),
                baselineMinM = f("baselineMinM", d.baselineMinM),
                keepMovingAfterMs = l("keepMovingAfterMs", d.keepMovingAfterMs),
                spinRotationEnterDeg = f("spinRotationEnterDeg", d.spinRotationEnterDeg),
                spinRotationExitDeg = f("spinRotationExitDeg", d.spinRotationExitDeg),
                spinTranslationEnterM = f("spinTranslationEnterM", d.spinTranslationEnterM),
                spinTranslationExitM = f("spinTranslationExitM", d.spinTranslationExitM),
                spinWindowMs = l("spinWindowMs", d.spinWindowMs),
                jitterEnter = f("jitterEnter", d.jitterEnter),
                jitterExit = f("jitterExit", d.jitterExit),
                jitterMaxLinearVelocity = f("jitterMaxLinearVelocity", d.jitterMaxLinearVelocity),
                linearVelocityMax = f("linearVelocityMax", d.linearVelocityMax),
                thermalWarnStatus = i("thermalWarnStatus", d.thermalWarnStatus),
                stopMinScanMs = l("stopMinScanMs", d.stopMinScanMs),
                stopMinPathM = f("stopMinPathM", d.stopMinPathM),
                stopMinKeyframes = i("stopMinKeyframes", d.stopMinKeyframes),
                stopMinExtentM = f("stopMinExtentM", d.stopMinExtentM),
                imageMaxAgeMs = l("imageMaxAgeMs", d.imageMaxAgeMs),
                debounceFrames = i("debounceFrames", d.debounceFrames),
                minDisplayMs = l("minDisplayMs", d.minDisplayMs),
                cooldownWindowMs = l("cooldownWindowMs", d.cooldownWindowMs),
                cooldownMaxShows = i("cooldownMaxShows", d.cooldownMaxShows),
                settleMs = l("settleMs", d.settleMs),
                trackingStableMs = l("trackingStableMs", d.trackingStableMs),
                trackingLostGraceMs = l("trackingLostGraceMs", d.trackingLostGraceMs),
                maxSessionMs = l("maxSessionMs", d.maxSessionMs),
                warnBeforeEndMs = l("warnBeforeEndMs", d.warnBeforeEndMs),
                imageSampleInterval = i("imageSampleInterval", d.imageSampleInterval)
            )
        }
    }
}
