package com.example.live2davatarai.audio

import android.media.audiofx.Visualizer
import android.util.Log
import kotlin.math.abs

class AudioAnalyzer(private val onAmplitudeChanged: (Float) -> Unit) {
    private var visualizer: Visualizer? = null

    fun start(audioSessionId: Int = 0) {
        stop() // Stop any previous instance
        
        try {
            Log.d("AudioAnalyzer", "Starting Visualizer on session $audioSessionId")
            visualizer = Visualizer(audioSessionId).apply {
                this.captureSize = captureSize
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                        waveform?.let {
                            val amplitude = calculateAmplitude(it)
                            onAmplitudeChanged(amplitude)
                        }
                    }

                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {}
                }, Visualizer.getMaxCaptureRate() / 2, true, false)
                enabled = true
            }
            Log.d("AudioAnalyzer", "Visualizer started successfully")
        } catch (e: Exception) {
            Log.e("AudioAnalyzer", "Failed to start Visualizer: ${e.message}")
            visualizer = null
        }
    }

    private fun calculateAmplitude(waveform: ByteArray): Float {
        if (waveform.isEmpty()) return 0f
        var sum = 0f
        for (i in waveform.indices) {
            val value = (waveform[i].toInt() and 0xFF) - 128
            sum += abs(value.toFloat())
        }
        val avg = sum / waveform.size
        // Hyper sensitivity: dividing by 8 instead of 16
        // This will make even small sounds trigger mouth movement
        return (avg / 8f).coerceIn(0f, 1.0f)
    }

    fun stop() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (e: Exception) {
            Log.e("AudioAnalyzer", "Error stopping visualizer: ${e.message}")
        }
        visualizer = null
    }
}
