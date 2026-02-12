package com.example.live2davatarai.ui

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import com.example.live2davatarai.engine.AvatarController
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class AvatarSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val renderer: Live2DRenderer

    init {
        setEGLContextClientVersion(2)
        renderer = Live2DRenderer()
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun setController(controller: AvatarController) {
        renderer.attachController(controller)
    }
}

class Live2DRenderer : GLSurfaceView.Renderer {
    private var controller: AvatarController? = null

    fun attachController(controller: AvatarController) {
        this.controller = controller
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Initialize Live2D Cubism SDK here
        // LAppLive2DManager.init()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        // Handle viewport changes
    }

    override fun onDrawFrame(gl: GL10?) {
        controller?.updateAnimations()
        
        // In a real implementation, you would now pass 
        // controller.mouthOpenY, controller.bodyAngleX, etc. 
        // to the Live2D model instance:
        // model.setParameterValue("ParamMouthOpenY", controller.mouthOpenY)
        
        // Clear screen with a simple color (e.g., dark gray)
        gl?.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        gl?.glClear(GL10.GL_COLOR_BUFFER_BIT)
    }
}
