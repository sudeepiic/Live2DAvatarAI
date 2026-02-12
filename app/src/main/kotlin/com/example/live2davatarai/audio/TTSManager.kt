package com.example.live2davatarai.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class TTSManager(context: Context, private val onSpeakingFinished: () -> Unit) {
    private var tts: TextToSpeech? = null
    private var isReady = false

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("TTSManager", "Language not supported")
                } else {
                    isReady = true
                    setupProgressListener()
                }
            } else {
                Log.e("TTSManager", "Initialization failed")
            }
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d("TTSManager", "Started speaking")
            }

            override fun onDone(utteranceId: String?) {
                Log.d("TTSManager", "Finished speaking")
                onSpeakingFinished()
            }

            override fun onError(utteranceId: String?) {
                Log.e("TTSManager", "Error speaking")
                onSpeakingFinished()
            }
        })
    }

    fun speak(text: String) {
        if (isReady && text.isNotBlank()) {
            Log.d("TTSManager", "Speaking: $text")
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "utteranceId_" + System.currentTimeMillis())
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.e("TTSManager", "Error stopping: ${e.message}")
        }
    }

    fun destroy() {
        try {
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e("TTSManager", "Error shutdown: ${e.message}")
        }
    }
}
