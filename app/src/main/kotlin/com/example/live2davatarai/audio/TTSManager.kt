package com.example.live2davatarai.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.live2davatarai.util.LogUtil
import java.util.Locale

class TTSManager(context: Context, private val onSpeakingFinished: () -> Unit) {
    private var tts: TextToSpeech? = null
    var isReady = false
        private set
    
    var currentAudioSessionId: Int = 0
        private set

    init {
        LogUtil.d("TTSManager", "Initializing TextToSpeech...")
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                LogUtil.d("TTSManager", "TTS Engine initialized successfully")
                val result = tts?.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    LogUtil.e("TTSManager", "Language not supported")
                } else {
                    // Reset to more natural settings
                    tts?.setPitch(0.9f)
                    tts?.setSpeechRate(1.0f)
                    
                    // Look for a high-quality male voice
                    try {
                        val voices = tts?.voices
                        // Prefer "en-us" or "en-gb" male voices
                        val bestVoice = voices?.filter { 
                            it.name.contains("male", ignoreCase = true) && 
                            (it.locale.language == "en")
                        }?.maxByOrNull { it.quality } ?: voices?.find { it.name.contains("male", ignoreCase = true) }
                        
                        if (bestVoice != null) {
                            tts?.voice = bestVoice
                            LogUtil.d("TTSManager", "Selected voice")
                        }
                    } catch (e: Exception) {
                        LogUtil.e("TTSManager", "Voice selection failed: ${e.message}")
                    }

                    isReady = true
                    setupProgressListener()
                }
            } else {
                LogUtil.e("TTSManager", "Initialization failed")
            }
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                LogUtil.d("TTSManager", "Started speaking")
            }

            override fun onDone(utteranceId: String?) {
                LogUtil.d("TTSManager", "Finished speaking")
                onSpeakingFinished()
            }

            @Deprecated("Deprecated in API 21")
            override fun onError(utteranceId: String?) {
                onError(utteranceId, TextToSpeech.ERROR)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                LogUtil.e("TTSManager", "Error speaking")
                onSpeakingFinished()
            }
        })
    }

    fun speak(text: String) {
        if (!isReady) {
            LogUtil.e("TTSManager", "TTS not ready yet")
            return
        }
        if (text.isNotBlank()) {
            LogUtil.d("TTSManager", "Attempting to speak")
            val params = android.os.Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            val result = tts?.speak(text, TextToSpeech.QUEUE_ADD, params, "utteranceId_" + System.currentTimeMillis())
            if (result == TextToSpeech.ERROR) {
                LogUtil.e("TTSManager", "Speak call failed with ERROR")
            }
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            LogUtil.e("TTSManager", "Error stopping: ${e.message}")
        }
    }

    fun destroy() {
        try {
            tts?.shutdown()
        } catch (e: Exception) {
            LogUtil.e("TTSManager", "Error shutdown: ${e.message}")
        }
    }
}
