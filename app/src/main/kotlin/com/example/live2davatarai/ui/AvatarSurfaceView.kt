package com.example.live2davatarai.ui

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import com.example.live2davatarai.engine.AvatarController
import com.example.live2davatarai.engine.AvatarExpression
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

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
    private var width = 0
    private var height = 0
    
    // Shader code
    private val vertexShaderCode = """
        attribute vec4 vPosition;
        uniform mat4 uMVPMatrix;
        void main() {
            gl_Position = uMVPMatrix * vPosition;
        }
    """
    
    private val fragmentShaderCode = """
        precision mediump float;
        uniform vec4 vColor;
        void main() {
            gl_FragColor = vColor;
        }
    """

    private var program: Int = 0

    fun attachController(controller: AvatarController) {
        this.controller = controller
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.15f, 1.0f)
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        this.width = width
        this.height = height
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        controller?.updateAnimations()

        val mouthOpen = controller?.mouthOpenY ?: 0f
        val tilt = controller?.bodyAngleX ?: 0f
        val eyeOpen = controller?.eyeOpen ?: 1f
        val expression = controller?.currentExpression ?: AvatarExpression.NEUTRAL

        // Basic Math for Layout
        val ratio = width.toFloat() / height.toFloat()
        
        // Draw Head (Circle) - Wanko Brown
        drawCircle(0f, 0f, 0.5f, floatArrayOf(0.6f, 0.4f, 0.2f, 1.0f), ratio)

        // Draw Eyes (Ellipses)
        val eyeColor = when(expression) {
            AvatarExpression.ANGRY -> floatArrayOf(0.8f, 0.2f, 0.2f, 1.0f)
            AvatarExpression.SLEEPY -> floatArrayOf(0.5f, 0.5f, 0.8f, 1.0f)
            else -> floatArrayOf(0.2f, 0.2f, 0.2f, 1.0f)
        }
        
        val blinkHeight = 0.1f * eyeOpen
        drawCircle(-0.2f, 0.1f, 0.1f, eyeColor, ratio, scaleY = blinkHeight/0.1f) // Left Eye
        drawCircle(0.2f, 0.1f, 0.1f, eyeColor, ratio, scaleY = blinkHeight/0.1f)  // Right Eye

        // Draw Mouth (Rectangle/Oval)
        val mouthHeight = 0.02f + (mouthOpen * 0.15f)
        drawCircle(0f, -0.2f, mouthHeight, floatArrayOf(0.6f, 0.3f, 0.3f, 1.0f), ratio, scaleX = 2.0f) // Mouth
    }

    private fun drawCircle(x: Float, y: Float, radius: Float, color: FloatArray, ratio: Float, scaleX: Float = 1f, scaleY: Float = 1f) {
        GLES20.glUseProgram(program)

        val vertices = FloatArray(364 * 3)
        vertices[0] = x
        vertices[1] = y
        vertices[2] = 0f

        for (i in 1..363) {
            val angle = (i * Math.PI / 180f).toFloat()
            vertices[i * 3] = x + (cos(angle) * radius * scaleX) / (if(ratio > 1) ratio else 1f)
            vertices[i * 3 + 1] = y + (sin(angle) * radius * scaleY) * (if(ratio < 1) ratio else 1f)
            vertices[i * 3 + 2] = 0f
        }

        val vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(vertices)
                position(0)
            }
        }

        val positionHandle = GLES20.glGetAttribLocation(program, "vPosition")
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        val colorHandle = GLES20.glGetUniformLocation(program, "vColor")
        GLES20.glUniform4fv(colorHandle, 1, color, 0)
        
        // Identity Matrix for simplicity
        val mvpMatrix = FloatArray(16).apply { 
            android.opengl.Matrix.setIdentityM(this, 0) 
        }
        val mvpHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 364)
        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }
}
