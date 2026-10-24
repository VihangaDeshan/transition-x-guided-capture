// SPDX-License-Identifier: Apache-2.0
// signals: pure image measurement on the luminance plane (TDD v1.0 section 9). No Android types.
package lk.transitionx.capture.signals

data class ImageStats(
    /** Variance of the 3x3 Laplacian on the 160x120 downsample. Higher is sharper. */
    val blurScore: Float,
    /** Mean luminance 0..255. */
    val brightness: Float,
    /** Percentage (0..100) of pixels above [ImageMetrics.CLIP_HIGH]. */
    val clippedHigh: Float,
    /** Percentage (0..100) of pixels below [ImageMetrics.CLIP_LOW]. */
    val clippedLow: Float,
    /**
     * Fraction (0..1) of 8x8 blocks whose luminance standard deviation exceeds
     * [ImageMetrics.TEXTURE_BLOCK_STD]. Low on blank walls and ceilings; independent of blur at this
     * scale. Replaces the ARCore point count for S1, which was 0-11 in a furnished room on the A55.
     */
    val textureFraction: Float = 0f,
    /**
     * Laplacian variance divided by mean 8x8 block variance (+1). Contrast-normalised, so a plain
     * but sharp surface is not mistaken for blur, and portable across camera gains. Real capture:
     * median 0.67 while turning > 30 deg/s, 1.93 while still.
     */
    val sharpness: Float = 0f,
    /**
     * Percentage of 8x8 blocks holding a near-saturated pixel (>= 250) while the block mean is
     * below 170: small glare highlights inside darker surroundings, typical of glass, mirrors and
     * polished surfaces (S4, experimental). A bright window instead raises clippedHigh (L2).
     */
    val specularPercent: Float = 0f
)

object ImageMetrics {
    const val TARGET_W = 160
    const val TARGET_H = 120
    const val CLIP_HIGH = 250
    const val CLIP_LOW = 5
    const val BLOCK = 8
    const val TEXTURE_BLOCK_STD = 8f
    const val SPECULAR_PEAK = 250
    const val SPECULAR_BLOCK_MEAN = 170.0

    /**
     * Box-average downsample of a luminance plane to [TARGET_W] x [TARGET_H].
     * Averaging (rather than picking every n-th pixel) keeps sensor noise from inflating the
     * Laplacian variance, so the blur score depends on the scene, not on the decimation.
     */
    fun downsample(src: ByteArray, srcW: Int, srcH: Int, rowStride: Int = srcW): ByteArray {
        require(srcW > 0 && srcH > 0) { "empty image" }
        require(rowStride >= srcW) { "rowStride < width" }
        require(src.size >= rowStride * (srcH - 1) + srcW) { "buffer too small" }
        val out = ByteArray(TARGET_W * TARGET_H)
        for (oy in 0 until TARGET_H) {
            val y0 = oy * srcH / TARGET_H
            val y1 = maxOf(y0 + 1, (oy + 1) * srcH / TARGET_H)
            for (ox in 0 until TARGET_W) {
                val x0 = ox * srcW / TARGET_W
                val x1 = maxOf(x0 + 1, (ox + 1) * srcW / TARGET_W)
                var sum = 0
                var n = 0
                for (y in y0 until minOf(y1, srcH)) {
                    val row = y * rowStride
                    for (x in x0 until minOf(x1, srcW)) {
                        sum += src[row + x].toInt() and 0xFF
                        n++
                    }
                }
                out[oy * TARGET_W + ox] = (if (n == 0) 0 else sum / n).toByte()
            }
        }
        return out
    }

    /** Variance of the 4-neighbour Laplacian over the interior pixels. */
    fun laplacianVariance(img: ByteArray, w: Int, h: Int): Float {
        if (w < 3 || h < 3) return 0f
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            val r = y * w
            for (x in 1 until w - 1) {
                val c = img[r + x].toInt() and 0xFF
                val l = (img[r + x - 1].toInt() and 0xFF) + (img[r + x + 1].toInt() and 0xFF) +
                    (img[r - w + x].toInt() and 0xFF) + (img[r + w + x].toInt() and 0xFF) - 4 * c
                sum += l
                sumSq += l.toDouble() * l
                n++
            }
        }
        val mean = sum / n
        return (sumSq / n - mean * mean).toFloat().coerceAtLeast(0f)
    }

    fun analyzeDownsampled(img: ByteArray, w: Int = TARGET_W, h: Int = TARGET_H): ImageStats {
        var sum = 0L
        var hi = 0
        var lo = 0
        val total = w * h
        for (i in 0 until total) {
            val v = img[i].toInt() and 0xFF
            sum += v
            if (v > CLIP_HIGH) hi++
            if (v < CLIP_LOW) lo++
        }
        val blur = laplacianVariance(img, w, h)
        val blocks = blockStats(img, w, h)
        return ImageStats(
            blurScore = blur,
            brightness = sum.toFloat() / total,
            clippedHigh = hi * 100f / total,
            clippedLow = lo * 100f / total,
            textureFraction = blocks.texturedFraction,
            sharpness = blur / (blocks.meanVariance + 1f),
            specularPercent = blocks.specularPercent
        )
    }

    data class BlockStats(val texturedFraction: Float, val meanVariance: Float, val specularPercent: Float)

    /** (fraction of textured blocks, mean block variance) over non-overlapping BLOCK x BLOCK tiles. */
    fun blockContrast(img: ByteArray, w: Int, h: Int): Pair<Float, Float> =
        blockStats(img, w, h).let { it.texturedFraction to it.meanVariance }

    fun blockStats(img: ByteArray, w: Int, h: Int): BlockStats {
        val bw = w / BLOCK
        val bh = h / BLOCK
        if (bw == 0 || bh == 0) return BlockStats(0f, 0f, 0f)
        val thresholdVar = TEXTURE_BLOCK_STD * TEXTURE_BLOCK_STD
        val n = BLOCK * BLOCK
        var textured = 0
        var specular = 0
        var varSum = 0.0
        for (by in 0 until bh) {
            for (bx in 0 until bw) {
                var s = 0
                var sq = 0L
                var max = 0
                for (y in by * BLOCK until (by + 1) * BLOCK) {
                    val r = y * w
                    for (x in bx * BLOCK until (bx + 1) * BLOCK) {
                        val v = img[r + x].toInt() and 0xFF
                        s += v
                        sq += (v * v).toLong()
                        if (v > max) max = v
                    }
                }
                val mean = s.toDouble() / n
                val variance = (sq.toDouble() / n - mean * mean).coerceAtLeast(0.0)
                varSum += variance
                if (variance > thresholdVar) textured++
                if (max >= SPECULAR_PEAK && mean < SPECULAR_BLOCK_MEAN) specular++
            }
        }
        val blocks = bw * bh
        return BlockStats(textured.toFloat() / blocks, (varSum / blocks).toFloat(), specular * 100f / blocks)
    }

    /** Full measurement: downsample, then blur, brightness and clipping. */
    fun analyze(luma: ByteArray, width: Int, height: Int, rowStride: Int = width): ImageStats =
        analyzeDownsampled(downsample(luma, width, height, rowStride))
}
