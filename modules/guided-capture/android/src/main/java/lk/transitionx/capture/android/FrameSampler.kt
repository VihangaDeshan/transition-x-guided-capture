// SPDX-License-Identifier: Apache-2.0
// GL thread, cheap work only: turns an ARCore frame's depth image and point cloud into world-space
// points for the coverage model, the distance to the surface at the centre of the view (S2/S3)
// and the autofocus state (L5). Heavy work (voxels, rules) happens on the analysis thread.
package lk.transitionx.capture.android

import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.ImageMetadata
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder

class FrameSampler {
    class Sample(val points: FloatArray?, val fromDepth: Boolean, val centerDepthM: Float?)

    /** Camera2 CONTROL_AF_STATE of this frame, or -1. */
    fun afState(frame: Frame): Int = try {
        frame.imageMetadata.getByte(ImageMetadata.CONTROL_AF_STATE).toInt()
    } catch (e: Exception) {
        -1
    }

    /**
     * Depth samples on a GRID_W x GRID_H grid back-projected to world space (when the Depth API is
     * on), plus confident ARCore feature points. Returns null points when nothing is available.
     */
    fun sample(frame: Frame, depthEnabled: Boolean): Sample {
        val out = ArrayList<Float>(3 * (GRID_W * GRID_H + MAX_CLOUD))
        var fromDepth = false
        var centre: Float? = null
        val pose = frame.camera.pose
        if (depthEnabled) {
            try {
                val img = frame.acquireDepthImage16Bits()
                try {
                    val dw = img.width
                    val dh = img.height
                    val plane = img.planes[0]
                    val buf = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
                    val rowStride = plane.rowStride
                    val pixelStride = plane.pixelStride
                    val intr = frame.camera.textureIntrinsics
                    val dims = intr.imageDimensions
                    val sx = dw.toFloat() / dims[0]
                    val sy = dh.toFloat() / dims[1]
                    val fx = intr.focalLength[0] * sx
                    val fy = intr.focalLength[1] * sy
                    val cx = intr.principalPoint[0] * sx
                    val cy = intr.principalPoint[1] * sy
                    val centreValues = ArrayList<Float>()
                    for (gy in 0 until GRID_H) {
                        val v = ((gy + 0.5f) * dh / GRID_H).toInt().coerceIn(0, dh - 1)
                        for (gx in 0 until GRID_W) {
                            val u = ((gx + 0.5f) * dw / GRID_W).toInt().coerceIn(0, dw - 1)
                            val mm = buf.getShort(v * rowStride + u * pixelStride).toInt() and 0xFFFF
                            if (mm == 0 || mm > MAX_DEPTH_MM) continue
                            val d = mm / 1000f
                            if (gx in GRID_W / 3 until 2 * GRID_W / 3 && gy in GRID_H / 3 until 2 * GRID_H / 3) centreValues += d
                            // Camera frame: +X right, +Y up, looking down -Z (image v grows downwards).
                            val local = floatArrayOf((u - cx) / fx * d, -(v - cy) / fy * d, -d)
                            val w = pose.transformPoint(local)
                            out += w[0]; out += w[1]; out += w[2]
                            fromDepth = true
                        }
                    }
                    if (centreValues.size >= 3) {
                        centreValues.sort()
                        centre = centreValues[centreValues.size / 2]
                    }
                } finally {
                    img.close()
                }
            } catch (e: NotYetAvailableException) {
                // depth not ready this frame
            } catch (e: Exception) {
                Log.w(TAG, "depth sample", e)
            }
        }
        try {
            val pc = frame.acquirePointCloud()
            try {
                val pts = pc.points
                val n = pts.remaining() / 4
                var taken = 0
                for (i in 0 until n) {
                    if (taken >= MAX_CLOUD) break
                    if (pts.get(4 * i + 3) < MIN_CONFIDENCE) continue
                    out += pts.get(4 * i); out += pts.get(4 * i + 1); out += pts.get(4 * i + 2)
                    taken++
                }
            } finally {
                pc.close()
            }
        } catch (e: Exception) {
            // no point cloud this frame
        }
        return Sample(if (out.isEmpty()) null else out.toFloatArray(), fromDepth, centre)
    }

    companion object {
        private const val TAG = "GuidedCaptureSampler"
        const val GRID_W = 24
        const val GRID_H = 18
        const val MAX_CLOUD = 200
        const val MIN_CONFIDENCE = 0.2f
        const val MAX_DEPTH_MM = 8000
    }
}
