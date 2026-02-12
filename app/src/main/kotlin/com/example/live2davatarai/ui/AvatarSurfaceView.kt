package com.example.live2davatarai.ui

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import com.example.live2davatarai.engine.AvatarController
import com.live2d.demo.JniBridgeJava
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
        JniBridgeJava.nativeOnSurfaceCreated()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        JniBridgeJava.nativeOnSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        controller?.let {
            it.updateAnimations()
            JniBridgeJava.nativeUpdateParameters(
                it.mouthOpenY,
                it.bodyAngleX,
                it.eyeOpen,
                it.browY
            )
        }
        JniBridgeJava.nativeOnDrawFrame()
    }
}
