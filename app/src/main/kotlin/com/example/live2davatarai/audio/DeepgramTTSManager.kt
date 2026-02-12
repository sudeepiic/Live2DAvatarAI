package com.example.live2davatarai.audio

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class DeepgramTTSManager(
    context: Context,
    private val apiKey: String,
    private val onSpeakingFinished: () -> Unit
) {
    private var mediaPlayer: MediaPlayer? = null
    var isReady = false
    private var isPlaying = false
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // No timeout for streaming audio
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // TODO: Store audio files in cache for better performance
    private val audioCacheDir = File(context.cacheDir, "deepgram_tts")

    init {
        Log.d("DeepgramTTS", "Initializing Deepgram TTS...")
        audioCacheDir.mkdirs()
        mediaPlayer = MediaPlayer()
        isReady = true
        Log.d("DeepgramTTS", "Deepgram TTS initialized successfully")
    }

    fun speak(text: String) {
        if (!isReady) {
            Log.e("DeepgramTTS", "TTS not ready yet")
            return
        }
        if (text.isNotBlank()) {
            Log.d("DeepgramTTS", "Requesting TTS for: $text")
            requestTTS(text)
        }
    }

    private fun requestTTS(text: String) {
        // Deepgram TTS API endpoint
        val url = "https://api.deepgram.com/v1/speak?model=${MODEL}&encoding=${ENCODING}&container=${CONTAINER}"

        val requestBody = JSONObject().apply {
            put("text", text)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Token $apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("DeepgramTTS", "Network Failure: ${e.message}")
                onSpeakingFinished()
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    val errorMsg = "API Error ${response.code}: $errorBody"
                    Log.e("DeepgramTTS", errorMsg)
                    onSpeakingFinished()
                    response.close()
                    return
                }

                val audioData = response.body?.bytes()
                response.close()

                if (audioData != null) {
                    playAudio(audioData)
                } else {
                    Log.e("DeepgramTTS", "Empty response body")
                    onSpeakingFinished()
                }
            }
        })
    }

    private fun playAudio(audioData: ByteArray) {
        // Prevent duplicate playback
        if (isPlaying) {
            Log.d("DeepgramTTS", "Already playing, skipping duplicate request")
            return
        }

        try {
            mediaPlayer?.let { player ->
                player.reset()

                // Create a temporary file for MediaPlayer
                val tempAudioFile = File(audioCacheDir, "tts_${System.currentTimeMillis()}.mp3")
                tempAudioFile.writeBytes(audioData)

                player.setDataSource(tempAudioFile.absolutePath)
                player.prepareAsync()

                isPlaying = true
                Log.d("DeepgramTTS", "Starting playback")
                player.setOnPreparedListener {
                    player.start()
                }

                player.setOnCompletionListener {
                    Log.d("DeepgramTTS", "Playback finished")
                    isPlaying = false
                    onSpeakingFinished()
                }

                player.setOnErrorListener { _, _, _ ->
                    Log.e("DeepgramTTS", "Playback error occurred")
                    isPlaying = false
                    onSpeakingFinished()
                    true
                }
            }
        } catch (e: Exception) {
            Log.e("DeepgramTTS", "Error playing audio: ${e.message}")
            isPlaying = false
            onSpeakingFinished()
        }
    }

    fun stop() {
        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                    isPlaying = false
                    Log.d("DeepgramTTS", "Stopped playback")
                }
            }
        } catch (e: Exception) {
            Log.e("DeepgramTTS", "Error stopping: ${e.message}")
        }
    }

    fun destroy() {
        try {
            mediaPlayer?.release()
            mediaPlayer = null
            Log.d("DeepgramTTS", "Destroyed")
        } catch (e: Exception) {
            Log.e("DeepgramTTS", "Error destroying: ${e.message}")
        }
    }

    companion object {
        // You can adjust these parameters
        private const val MODEL = "aura-2-thalia-en" // High-quality English voice
        private const val ENCODING = "linear16" // 16-bit PCM
        private const val CONTAINER = "wav" // WAV container (mp3 is not supported)
    }
}
