// SPDX-License-Identifier: Apache-2.0
// "Erase the stripes" (IntelliCap, ISMAR 2025; TDD v1.0 section 7.3): surfaces seen from only one
// angle are drawn as pink-and-white striped discs on the camera view. Walking to see them from a
// second place covers them and the stripes disappear. GL thread only.
package lk.transitionx.capture.android

import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class CoverageOverlayRenderer {
    private var program = 0
    private var positionAttrib = 0
    private var mvpUniform = 0
    private var scaleUniform = 0
    private var buffer: FloatBuffer = alloc(3 * 512)
    private val viewProj = FloatArray(16)

    fun createOnGlThread() {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e(TAG, "overlay link failed: " + GLES20.glGetProgramInfoLog(program))
            GLES20.glDeleteProgram(program)
            program = 0
            return
        }
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        mvpUniform = GLES20.glGetUniformLocation(program, "u_ViewProjection")
        scaleUniform = GLES20.glGetUniformLocation(program, "u_PointScale")
    }

    /** [points] are voxel centres, packed x,y,z, in world space. */
    fun draw(view: FloatArray, projection: FloatArray, points: FloatArray, viewportHeight: Int, voxelSizeM: Float) {
        if (program == 0 || points.size < 3) return
        Matrix.multiplyMM(viewProj, 0, projection, 0, view, 0)
        if (buffer.capacity() < points.size) buffer = alloc(points.size)
        buffer.clear()
        buffer.put(points)
        buffer.position(0)
        // Projected size of a voxel: size * focal(px) / depth; focal(px) = proj[5] * height / 2.
        val scale = voxelSizeM * projection[5] * viewportHeight / 2f

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, viewProj, 0)
        GLES20.glUniform1f(scaleUniform, scale)
        GLES20.glVertexAttribPointer(positionAttrib, 3, GLES20.GL_FLOAT, false, 0, buffer)
        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, points.size / 3)
        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "overlay shader failed: " + GLES20.glGetShaderInfoLog(shader))
        return shader
    }

    companion object {
        private const val TAG = "GuidedCaptureOverlay"

        private const val VERTEX = """
uniform mat4 u_ViewProjection;
uniform float u_PointScale;
attribute vec4 a_Position;
void main() {
  gl_Position = u_ViewProjection * vec4(a_Position.xyz, 1.0);
  gl_PointSize = clamp(u_PointScale / max(gl_Position.w, 0.05), 4.0, 96.0);
}
"""

        private const val FRAGMENT = """
precision mediump float;
void main() {
  vec2 c = gl_PointCoord - vec2(0.5);
  if (dot(c, c) > 0.25) discard;
  float stripe = mod(gl_FragCoord.x + gl_FragCoord.y, 16.0);
  if (stripe < 8.0) {
    gl_FragColor = vec4(1.0, 0.36, 0.66, 0.55);
  } else {
    gl_FragColor = vec4(1.0, 1.0, 1.0, 0.22);
  }
}
"""

        private fun alloc(floats: Int): FloatBuffer =
            ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }
}
