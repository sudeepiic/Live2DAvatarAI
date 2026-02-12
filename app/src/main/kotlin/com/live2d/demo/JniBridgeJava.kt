package com.live2d.demo

import android.app.Activity
import android.content.Context
import java.io.IOException

object JniBridgeJava {
    @JvmStatic external fun nativeOnStart()
    @JvmStatic external fun nativeOnPause()
    @JvmStatic external fun nativeOnStop()
    @JvmStatic external fun nativeOnDestroy()
    @JvmStatic external fun nativeOnSurfaceCreated()
    @JvmStatic external fun nativeOnSurfaceChanged(width: Int, height: Int)
    @JvmStatic external fun nativeOnDrawFrame()
    @JvmStatic external fun nativeUpdateParameters(mouthOpenY: Float, bodyAngleX: Float, eyeOpen: Float, browY: Float)
    @JvmStatic external fun nativeOnTouchesBegan(pointX: Float, pointY: Float)
    @JvmStatic external fun nativeOnTouchesEnded(pointX: Float, pointY: Float)
    @JvmStatic external fun nativeOnTouchesMoved(pointX: Float, pointY: Float)

    private var activityInstance: Activity? = null
    private var context: Context? = null

    @JvmStatic
    fun SetContext(context: Context) {
        this.context = context
    }

    @JvmStatic
    fun SetActivityInstance(activity: Activity) {
        this.activityInstance = activity
    }

    @JvmStatic
    fun GetAssetList(dirPath: String): Array<String> {
        return try {
            context?.assets?.list(dirPath) ?: emptyArray()
        } catch (e: IOException) {
            e.printStackTrace()
            emptyArray()
        }
    }

    @JvmStatic
    fun LoadFile(filePath: String): ByteArray? {
        android.util.Log.d("JniBridgeJava", "Loading file: '$filePath'")
        return try {
            val inputStream = context?.assets?.open(filePath) ?: return null
            val size = inputStream.available()
            val buffer = ByteArray(size)
            inputStream.read(buffer)
            inputStream.close()
            buffer
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    @JvmStatic
    fun MoveTaskToBack() {
        activityInstance?.moveTaskToBack(true)
    }

    private var isLibraryLoaded = false

    init {
        try {
            System.loadLibrary("live2davatarai")
            isLibraryLoaded = true
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    fun isReady(): Boolean = isLibraryLoaded
}
