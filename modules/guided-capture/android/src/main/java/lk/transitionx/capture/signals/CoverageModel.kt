// SPDX-License-Identifier: Apache-2.0
// signals: coverage that measures what reconstruction needs (TDD v1.0 section 7.2).
//
// The room is divided into voxels (default 20 cm). Every observed 3D point marks its voxel as
// *observed* and records the direction from the camera to the voxel CENTRE. A voxel becomes
// *covered* once it has been seen along two directions at least `minParallaxDeg` apart, i.e.
// from two different places. Because the direction is taken to the voxel centre, turning on the
// spot never changes it: spinning in place produces observed but uncovered voxels, walking
// produces covered ones. That is the opposite of the direction-count metric the TDD rejects.
//
// The same model also answers the navigation questions for C1 (unseen arc of the room),
// C2 (never looked up/down), C3 (what is in view now is seen from one angle only) and
// C4 (a corner of the room footprint with no observations). Pure Kotlin; no platform types.
package lk.transitionx.capture.signals

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sqrt
import lk.transitionx.capture.core.CapturePose

data class CoverageConfig(
    val voxelSizeM: Float = 0.20f,
    val minParallaxDeg: Float = 15f,
    val minRangeM: Float = 0.25f,
    val maxRangeM: Float = 5f,
    /** Horizontal viewing directions are tracked in this many azimuth bins (C1). */
    val azimuthBins: Int = 12,
    /** Pitch beyond this counts as having looked up / down (C2). */
    val lookPitchDeg: Float = 30f,
    /** Half-angle of the "in view now" cone used for C3. */
    val viewConeDeg: Float = 30f,
    /** A footprint corner counts as observed if a voxel lies within this distance of it (C4). */
    val cornerRadiusM: Float = 0.75f,
    /** Hard cap on stored voxels (memory bound for a 10-minute session). */
    val maxVoxels: Int = 60_000
)

data class CoverageSnapshot(
    val observed: Int,
    val covered: Int,
    val unseenArcDeg: Float,
    val unseenArcYawDeg: Float?,
    val lookedUp: Boolean,
    val lookedDown: Boolean,
    val viewVoxelCount: Int,
    val viewSingleAngleFraction: Float,
    val missingCornerYawDeg: Float?
) {
    val fraction: Float get() = if (observed == 0) 0f else covered.toFloat() / observed

    companion object {
        val EMPTY = CoverageSnapshot(0, 0, 0f, null, false, false, 0, 0f, null)
    }
}

class CoverageModel(private val config: CoverageConfig = CoverageConfig()) {
    private class Cell(val cx: Float, val cy: Float, val cz: Float, val fx: Float, val fy: Float, val fz: Float) {
        var maxAngleDeg = 0f
        var hits = 1
    }

    private val cells = HashMap<Long, Cell>()
    private val azimuthSeen = BooleanArray(config.azimuthBins)
    private var lookedUp = false
    private var lookedDown = false
    var covered = 0
        private set
    val observed: Int get() = cells.size

    fun reset() {
        cells.clear()
        azimuthSeen.fill(false)
        lookedUp = false
        lookedDown = false
        covered = 0
    }

    /** Records where the camera is looking (C1, C2). Call on every tracked frame. */
    fun observeView(pose: CapturePose) {
        val f = pose.forward()
        val horiz = sqrt(f[0] * f[0] + f[2] * f[2])
        val pitch = Math.toDegrees(asin(f[1].coerceIn(-1f, 1f).toDouble())).toFloat()
        if (pitch > config.lookPitchDeg) lookedUp = true
        if (pitch < -config.lookPitchDeg) lookedDown = true
        if (horiz > 0.3f) azimuthSeen[azimuthBin(f)] = true
    }

    /**
     * Adds world-space points (packed x,y,z) seen from [camera]. Returns how many voxels became
     * covered by this call.
     */
    fun addPoints(camera: CapturePose, xyz: FloatArray, count: Int = xyz.size / 3): Int {
        val s = config.voxelSizeM
        var newlyCovered = 0
        for (i in 0 until count) {
            val px = xyz[3 * i]; val py = xyz[3 * i + 1]; val pz = xyz[3 * i + 2]
            if (!px.isFinite() || !py.isFinite() || !pz.isFinite()) continue
            val ix = floor(px / s).toInt(); val iy = floor(py / s).toInt(); val iz = floor(pz / s).toInt()
            val cx = (ix + 0.5f) * s; val cy = (iy + 0.5f) * s; val cz = (iz + 0.5f) * s
            val dx = cx - camera.tx; val dy = cy - camera.ty; val dz = cz - camera.tz
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d < config.minRangeM || d > config.maxRangeM) continue
            val ux = dx / d; val uy = dy / d; val uz = dz / d
            val key = key(ix, iy, iz)
            val cell = cells[key]
            if (cell == null) {
                if (cells.size < config.maxVoxels) cells[key] = Cell(cx, cy, cz, ux, uy, uz)
                continue
            }
            cell.hits++
            val dot = (ux * cell.fx + uy * cell.fy + uz * cell.fz).coerceIn(-1f, 1f)
            val angle = Math.toDegrees(acos(dot.toDouble())).toFloat()
            if (angle > cell.maxAngleDeg) {
                if (cell.maxAngleDeg < config.minParallaxDeg && angle >= config.minParallaxDeg) {
                    covered++
                    newlyCovered++
                }
                cell.maxAngleDeg = angle
            }
        }
        return newlyCovered
    }

    /** Navigation and completeness figures for the current camera pose. O(voxels). */
    fun snapshot(camera: CapturePose): CoverageSnapshot {
        val f = camera.forward()
        // C1: largest circular run of azimuth bins never looked at.
        val n = config.azimuthBins
        var bestLen = 0
        var bestStart = 0
        if (azimuthSeen.any { it }) {
            for (start in 0 until n) {
                if (azimuthSeen[start] || !azimuthSeen[(start - 1 + n) % n]) continue
                var len = 0
                while (len < n && !azimuthSeen[(start + len) % n]) len++
                if (len > bestLen) { bestLen = len; bestStart = start }
            }
        }
        val binDeg = 360f / n
        val unseenArc = bestLen * binDeg
        val arcYaw = if (bestLen == 0) null else {
            val centreAz = (bestStart + bestLen / 2f) * binDeg
            yawToAzimuth(f, centreAz)
        }

        // C3: voxels in the view cone, and how many are seen from one angle only.
        val cosCone = kotlin.math.cos(Math.toRadians(config.viewConeDeg.toDouble())).toFloat()
        var inView = 0
        var single = 0
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (c in cells.values) {
            if (c.hits >= 2) {
                if (c.cx < minX) minX = c.cx
                if (c.cx > maxX) maxX = c.cx
                if (c.cz < minZ) minZ = c.cz
                if (c.cz > maxZ) maxZ = c.cz
            }
            val dx = c.cx - camera.tx; val dy = c.cy - camera.ty; val dz = c.cz - camera.tz
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d < config.minRangeM || d > config.maxRangeM) continue
            if ((dx * f[0] + dy * f[1] + dz * f[2]) / d < cosCone) continue
            inView++
            if (c.maxAngleDeg < config.minParallaxDeg) single++
        }

        // C4: corners of the observed floor footprint with no voxel nearby.
        var cornerYaw: Float? = null
        if (minX < maxX && minZ < maxZ && maxX - minX >= 2f && maxZ - minZ >= 2f) {
            val r2 = config.cornerRadiusM * config.cornerRadiusM
            var bestDist = Float.MAX_VALUE
            for ((kx, kz) in listOf(minX to minZ, minX to maxZ, maxX to minZ, maxX to maxZ)) {
                val seen = cells.values.any { c -> c.hits >= 2 && (c.cx - kx) * (c.cx - kx) + (c.cz - kz) * (c.cz - kz) <= r2 }
                if (seen) continue
                val dx = kx - camera.tx; val dz = kz - camera.tz
                val dist = dx * dx + dz * dz
                if (dist < bestDist) {
                    bestDist = dist
                    cornerYaw = signedYawDeg(f[0], f[2], dx, dz)
                }
            }
        }

        return CoverageSnapshot(
            observed = cells.size,
            covered = covered,
            unseenArcDeg = unseenArc,
            unseenArcYawDeg = arcYaw,
            lookedUp = lookedUp,
            lookedDown = lookedDown,
            viewVoxelCount = inView,
            viewSingleAngleFraction = if (inView == 0) 0f else single.toFloat() / inView,
            missingCornerYawDeg = cornerYaw
        )
    }

    /** Centres of voxels seen from one angle only (for the "erase the stripes" overlay). */
    fun singleAngleCentres(camera: CapturePose, max: Int): FloatArray {
        val list = cells.values.filter { it.maxAngleDeg < config.minParallaxDeg }
            .sortedBy { (it.cx - camera.tx).let { x -> x * x } + (it.cz - camera.tz).let { z -> z * z } + (it.cy - camera.ty).let { y -> y * y } }
            .take(max)
        val out = FloatArray(list.size * 3)
        list.forEachIndexed { i, c -> out[3 * i] = c.cx; out[3 * i + 1] = c.cy; out[3 * i + 2] = c.cz }
        return out
    }

    /** All voxels for export: x, y, z (centre), parallax degrees, hits. */
    fun exportVoxels(): List<FloatArray> = cells.values.map { floatArrayOf(it.cx, it.cy, it.cz, it.maxAngleDeg, it.hits.toFloat()) }

    private fun azimuthBin(f: FloatArray): Int {
        var az = Math.toDegrees(atan2(f[0].toDouble(), (-f[2]).toDouble())).toFloat()
        if (az < 0) az += 360f
        return (az / (360f / config.azimuthBins)).toInt().coerceIn(0, config.azimuthBins - 1)
    }

    private fun yawToAzimuth(f: FloatArray, targetAzDeg: Float): Float {
        val rad = Math.toRadians(targetAzDeg.toDouble())
        // azimuth measured from -Z towards +X
        val tx = kotlin.math.sin(rad).toFloat()
        val tz = (-kotlin.math.cos(rad)).toFloat()
        return signedYawDeg(f[0], f[2], tx, tz)
    }

    companion object {
        private const val OFF = 1 shl 20

        private fun key(ix: Int, iy: Int, iz: Int): Long =
            ((ix + OFF).toLong() shl 42) or ((iy + OFF).toLong() shl 21) or (iz + OFF).toLong()

        /**
         * Signed horizontal angle from forward (fx, fz) to target (tx, tz), degrees, positive when
         * the target is to the right (Y up, right-handed; facing -Z, +X is to the right).
         */
        fun signedYawDeg(fx: Float, fz: Float, tx: Float, tz: Float): Float {
            val cross = fz * tx - fx * tz   // y-component of f x t
            val dot = fx * tx + fz * tz
            if (abs(cross) < 1e-9f && abs(dot) < 1e-9f) return 0f
            return Math.toDegrees(atan2((-cross).toDouble(), dot.toDouble())).toFloat()
        }
    }
}
