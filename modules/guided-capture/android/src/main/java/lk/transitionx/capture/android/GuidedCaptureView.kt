// SPDX-License-Identifier: Apache-2.0
// The camera/AR view hosted inside the React Native screen. ARCore renders to this surface;
// JavaScript never touches it (TDD v1.0 section 11.1).
package lk.transitionx.capture.android

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.ViewGroup
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.views.ExpoView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class GuidedCaptureView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {
    override val shouldUseAndroidLayout: Boolean
        get() = true

    private val controller = CaptureController.get(context)

    val glView: GLSurfaceView = GLSurfaceView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(object : GLSurfaceView.Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) = controller.onSurfaceCreated()
            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) = controller.onSurfaceChanged(width, height)
            override fun onDrawFrame(gl: GL10?) = controller.onDrawFrame()
        })
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        setWillNotDraw(false)
    }

    private var attached = false

    init {
        addView(glView)
        keepScreenOn = true // the screen must not sleep mid-capture
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!attached) {
            attached = true
            controller.attachView(this)
        }
    }

    override fun onDetachedFromWindow() {
        if (attached) {
            attached = false
            controller.detachView(this)
        }
        super.onDetachedFromWindow()
    }
}
