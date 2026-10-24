// SPDX-License-Identifier: Apache-2.0
// guidance: threshold set. Values are TDD v1.0 section 19 starting points, NOT calibrated
// constants. The canonical copy lives in config/thresholds.v1.json and is passed in at runtime,
// so a calibration result is a data change, not a code change.
package lk.transitionx.capture.guidance

data class Thresholds(
    val version: String = "1.2-phase23",

    // G1 turn too fast (deg/s), hysteresis enter/exit
    val angularVelocityEnter: Float = 45f,
    val angularVelocityExit: Float = 35f,

    // L3 motion blur: contrast-normalised sharpness while the phone is moving
    val sharpnessEnter: Float = 1.0f,
    val sharpnessExit: Float = 1.3f,
    val blurMotionDegPerSec: Float = 10f,
    val blurMotionLinearMps: Float = 0.4f,
    // L3 fallback for logs without sharpness (v1.0): Laplacian variance on 160x120
    val blurEnter: Float = 100f,
    val blurExit: Float = 150f,

    // L1 too dark (mean luminance 0..255)
    val darkEnter: Float = 40f,
    val darkExit: Float = 50f,

    // L2 too bright (% of pixels above 250)
    val clipHighEnter: Float = 15f,
    val clipHighExit: Float = 10f,

    // S1 blank wall: share of textured 8x8 blocks (the ARCore point count is not reliable)
    val textureEnter: Float = 0.15f,
    val textureExit: Float = 0.25f,

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
    val stopMinExtentM: Float = 1.0f,

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
    val imageSampleInterval: Int = 4,

    // ---- Phase 2 / 3 (v1.2) ----
    // G5 move smoothly: change of angular velocity, deg/s^2
    val angularAccelEnter: Float = 400f,
    val angularAccelExit: Float = 250f,
    // L4 clean the lens: soft image while the phone is still, in good light, on a detailed scene
    val lensSharpnessMax: Float = 1.0f,
    val lensMinTexture: Float = 0.25f,
    val lensSustainMs: Long = 5000,
    // L5 wait for focus: autofocus scanning while the image is soft
    val focusSustainMs: Long = 1000,
    // S2 / S3 distance to the surface in view (Depth API; absent without depth)
    val tooCloseM: Float = 0.5f,
    val tooCloseExitM: Float = 0.6f,
    val tooFarM: Float = 4.0f,
    val tooFarExitM: Float = 3.5f,
    // S4 mirror / glass (experimental): glare blocks, percent of the image
    val specularMinPercent: Float = 2f,
    val specularMaxPercent: Float = 15f,
    val reflectionSustainMs: Long = 1500,
    // C1 / C2 / C3 / C4 navigation, shown only after this much scanning
    val coverageGuidanceAfterMs: Long = 15_000,
    val unseenArcMinDeg: Float = 60f,
    val turnMinYawDeg: Float = 45f,
    val lookUpDownAfterMs: Long = 30_000,
    val needViewsFraction: Float = 0.7f,
    val needViewsMinVoxels: Int = 15,
    val needViewsSustainMs: Long = 3000,
    val cornerAfterMs: Long = 45_000,
    val cornerMinVoxels: Int = 150,
    // C5 return to the start (loop closure)
    val loopAfterMs: Long = 40_000,
    val loopMinAwayM: Float = 2.5f,
    val loopReturnM: Float = 1.0f,
    // T7 redo a poor stretch
    val segmentWindowMs: Long = 5000,
    val segmentBadRatio: Float = 0.6f,
    // C6 with measured coverage
    val stopMinCoverage: Float = 0.5f,
    val stopCoverageMinVoxels: Int = 60,
    // Coverage model
    val voxelSizeM: Float = 0.2f,
    val minParallaxDeg: Float = 15f,
) {
    companion object {
        /** Builds a set from a loosely typed map (parsed JSON); unknown keys are ignored. */
        fun fromMap(map: Map<String, Any?>): Thresholds {
            val d = Thresholds()
            fun f(k: String, def: Float): Float = (map[k] as? Number)?.toFloat() ?: def
            fun i(k: String, def: Int): Int = (map[k] as? Number)?.toInt() ?: def
            fun l(k: String, def: Long): Long = (map[k] as? Number)?.toLong() ?: def
            return validated(Thresholds(
                version = (map["version"] as? String) ?: d.version,
                angularVelocityEnter = f("angularVelocityEnter", d.angularVelocityEnter),
                angularVelocityExit = f("angularVelocityExit", d.angularVelocityExit),
                sharpnessEnter = f("sharpnessEnter", d.sharpnessEnter),
                sharpnessExit = f("sharpnessExit", d.sharpnessExit),
                blurMotionDegPerSec = f("blurMotionDegPerSec", d.blurMotionDegPerSec),
                blurMotionLinearMps = f("blurMotionLinearMps", d.blurMotionLinearMps),
                blurEnter = f("blurEnter", d.blurEnter),
                blurExit = f("blurExit", d.blurExit),
                darkEnter = f("darkEnter", d.darkEnter),
                darkExit = f("darkExit", d.darkExit),
                clipHighEnter = f("clipHighEnter", d.clipHighEnter),
                clipHighExit = f("clipHighExit", d.clipHighExit),
                textureEnter = f("textureEnter", d.textureEnter),
                textureExit = f("textureExit", d.textureExit),
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
                imageSampleInterval = i("imageSampleInterval", d.imageSampleInterval),
                angularAccelEnter = f("angularAccelEnter", d.angularAccelEnter),
                angularAccelExit = f("angularAccelExit", d.angularAccelExit),
                lensSharpnessMax = f("lensSharpnessMax", d.lensSharpnessMax),
                lensMinTexture = f("lensMinTexture", d.lensMinTexture),
                lensSustainMs = l("lensSustainMs", d.lensSustainMs),
                focusSustainMs = l("focusSustainMs", d.focusSustainMs),
                tooCloseM = f("tooCloseM", d.tooCloseM),
                tooCloseExitM = f("tooCloseExitM", d.tooCloseExitM),
                tooFarM = f("tooFarM", d.tooFarM),
                tooFarExitM = f("tooFarExitM", d.tooFarExitM),
                specularMinPercent = f("specularMinPercent", d.specularMinPercent),
                specularMaxPercent = f("specularMaxPercent", d.specularMaxPercent),
                reflectionSustainMs = l("reflectionSustainMs", d.reflectionSustainMs),
                coverageGuidanceAfterMs = l("coverageGuidanceAfterMs", d.coverageGuidanceAfterMs),
                unseenArcMinDeg = f("unseenArcMinDeg", d.unseenArcMinDeg),
                turnMinYawDeg = f("turnMinYawDeg", d.turnMinYawDeg),
                lookUpDownAfterMs = l("lookUpDownAfterMs", d.lookUpDownAfterMs),
                needViewsFraction = f("needViewsFraction", d.needViewsFraction),
                needViewsMinVoxels = i("needViewsMinVoxels", d.needViewsMinVoxels),
                needViewsSustainMs = l("needViewsSustainMs", d.needViewsSustainMs),
                cornerAfterMs = l("cornerAfterMs", d.cornerAfterMs),
                cornerMinVoxels = i("cornerMinVoxels", d.cornerMinVoxels),
                loopAfterMs = l("loopAfterMs", d.loopAfterMs),
                loopMinAwayM = f("loopMinAwayM", d.loopMinAwayM),
                loopReturnM = f("loopReturnM", d.loopReturnM),
                segmentWindowMs = l("segmentWindowMs", d.segmentWindowMs),
                segmentBadRatio = f("segmentBadRatio", d.segmentBadRatio),
                stopMinCoverage = f("stopMinCoverage", d.stopMinCoverage),
                stopCoverageMinVoxels = i("stopCoverageMinVoxels", d.stopCoverageMinVoxels),
                voxelSizeM = f("voxelSizeM", d.voxelSizeM),
                minParallaxDeg = f("minParallaxDeg", d.minParallaxDeg)
            ))
        }

        /**
         * Rejects values that would break the session rather than tune it (untrusted input from
         * JavaScript or a file): negative or zero timings, inverted hysteresis, absurd limits.
         */
        fun validated(t: Thresholds): Thresholds = t.copy(
            version = t.version.take(64).filter { it.isLetterOrDigit() || it in ".-_" }.ifEmpty { "custom" },
            angularVelocityExit = minOf(t.angularVelocityExit, t.angularVelocityEnter),
            blurExit = maxOf(t.blurExit, t.blurEnter),
            sharpnessExit = maxOf(t.sharpnessExit, t.sharpnessEnter),
            darkExit = maxOf(t.darkExit, t.darkEnter),
            clipHighExit = minOf(t.clipHighExit, t.clipHighEnter),
            textureEnter = t.textureEnter.coerceIn(0f, 1f),
            textureExit = maxOf(t.textureExit, t.textureEnter).coerceIn(0f, 1f),
            spinRotationExitDeg = minOf(t.spinRotationExitDeg, t.spinRotationEnterDeg),
            spinTranslationExitM = maxOf(t.spinTranslationExitM, t.spinTranslationEnterM),
            spinWindowMs = t.spinWindowMs.coerceIn(500, 10_000),
            jitterExit = minOf(t.jitterExit, t.jitterEnter),
            debounceFrames = t.debounceFrames.coerceIn(1, 30),
            minDisplayMs = t.minDisplayMs.coerceIn(0, 10_000),
            cooldownWindowMs = t.cooldownWindowMs.coerceIn(1_000, 120_000),
            cooldownMaxShows = t.cooldownMaxShows.coerceIn(1, 20),
            settleMs = t.settleMs.coerceIn(0, 10_000),
            trackingStableMs = t.trackingStableMs.coerceIn(0, 10_000),
            trackingLostGraceMs = t.trackingLostGraceMs.coerceIn(0, 10_000),
            maxSessionMs = t.maxSessionMs.coerceIn(30_000, 3_600_000),
            warnBeforeEndMs = t.warnBeforeEndMs.coerceIn(0, 600_000),
            imageSampleInterval = t.imageSampleInterval.coerceIn(1, 30),
            imageMaxAgeMs = t.imageMaxAgeMs.coerceIn(100, 10_000),
            keepMovingAfterMs = t.keepMovingAfterMs.coerceIn(500, 60_000),
            baselineMinM = t.baselineMinM.coerceIn(0.01f, 2f),
            angularAccelExit = minOf(t.angularAccelExit, t.angularAccelEnter),
            tooCloseExitM = maxOf(t.tooCloseExitM, t.tooCloseM),
            tooFarExitM = minOf(t.tooFarExitM, t.tooFarM),
            specularMaxPercent = maxOf(t.specularMaxPercent, t.specularMinPercent),
            lensSustainMs = t.lensSustainMs.coerceIn(0, 60_000),
            focusSustainMs = t.focusSustainMs.coerceIn(0, 60_000),
            reflectionSustainMs = t.reflectionSustainMs.coerceIn(0, 60_000),
            needViewsSustainMs = t.needViewsSustainMs.coerceIn(0, 60_000),
            needViewsFraction = t.needViewsFraction.coerceIn(0f, 1f),
            segmentWindowMs = t.segmentWindowMs.coerceIn(1_000, 60_000),
            segmentBadRatio = t.segmentBadRatio.coerceIn(0.1f, 1f),
            stopMinCoverage = t.stopMinCoverage.coerceIn(0f, 1f),
            voxelSizeM = t.voxelSizeM.coerceIn(0.05f, 1f),
            minParallaxDeg = t.minParallaxDeg.coerceIn(2f, 90f)
        )
    }
}
