package com.example.live2davatarai.audio

import android.media.audiofx.Visualizer
import kotlin.math.abs

class AudioAnalyzer(private val onAmplitudeChanged: (Float) -> Unit) {
    private var visualizer: Visualizer? = null

    fun start(audioSessionId: Int = 0) {
        try {
            visualizer = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun calculateAmplitude(waveform: ByteArray): Float {
        var sum = 0f
        for (i in 0 until waveform.size) {
            val value = (waveform[i].toInt() and 0xFF) - 128
            sum += abs(value.toFloat())
        }
        val avg = sum / waveform.size
        // Normalize to 0.0 - 1.0 range based on typical speech levels
        return (avg / 32f).coerceIn(0f, 1.0f)
    }

    fun stop() {
        visualizer?.enabled = false
        visualizer?.release()
        visualizer = null
    }
}
