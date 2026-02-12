package com.example.live2davatarai.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DeepgramStreamingTTSManager(
    private val context: Context,
    private val apiKey: String,
    private val onSpeakingFinished: () -> Unit
) {
    private var audioTrack: AudioTrack? = null
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()

    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private val pendingTextQueue = LinkedBlockingQueue<String>()
    
    @Volatile private var isSessionActive = false
    @Volatile private var isTextFinished = false
    @Volatile private var isConnected = false
    @Volatile private var playbackThread: Thread? = null

    companion object {
        private const val TAG = "DeepgramTTS"
        private const val SAMPLE_RATE = 16000
        private const val MODEL = "aura-asteria-en"
        private const val WS_URL = "wss://api.deepgram.com/v1/speak?model=$MODEL&encoding=linear16&sample_rate=$SAMPLE_RATE&container=none"
    }

    init {
        // Pre-connect to be ready
        connect()
    }

    private fun createAudioTrack(): AudioTrack {
        val bufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(32000)

        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().apply {
                setVolume(AudioTrack.getMaxVolume())
            }
    }

    fun connect() {
        if (isConnected && webSocket != null) return
        
        val request = Request.Builder()
            .url(WS_URL)
            .addHeader("Authorization", "Token $apiKey")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket OPEN")
                isConnected = true
                while (pendingTextQueue.isNotEmpty()) {
                    pendingTextQueue.poll()?.let { sendToWs(it) }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (data.isNotEmpty()) {
                    Log.v(TAG, "Received audio: ${data.size} bytes")
                    audioQueue.put(data)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket FAIL: ${t.message}")
                isConnected = false
                this@DeepgramStreamingTTSManager.webSocket = null
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                this@DeepgramStreamingTTSManager.webSocket = null
            }
        })
    }

    fun startStream() {
        Log.d(TAG, "--- Session Start ---")
        stopPlayback()
        
        isSessionActive = true
        isTextFinished = false
        audioQueue.clear()
        
        audioTrack = createAudioTrack()
        audioTrack?.play()
        
        connect()
        startPlaybackThread()
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        Log.d(TAG, "speak() -> $text")
        
        if (!isConnected) {
            pendingTextQueue.put(text)
            connect()
            return
        }
        sendToWs(text)
    }

    private fun sendToWs(text: String) {
        try {
            val msg = JSONObject().apply {
                put("type", "Speak")
                put("text", text)
            }
            webSocket?.send(msg.toString())
            webSocket?.send(JSONObject().apply { put("type", "Flush") }.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Send Error: ${e.message}")
            pendingTextQueue.put(text)
        }
    }

    fun endStream() {
        Log.d(TAG, "endStream()")
        isTextFinished = true
    }

    private fun startPlaybackThread() {
        playbackThread = Thread {
            var lastDataTime = System.currentTimeMillis()
            while (isSessionActive) {
                try {
                    val data = audioQueue.poll(100, TimeUnit.MILLISECONDS)
                    if (data != null) {
                        audioTrack?.write(data, 0, data.size)
                        lastDataTime = System.currentTimeMillis()
                    } else {
                        val idleTime = System.currentTimeMillis() - lastDataTime
                        val timeout = if (isTextFinished) 3000L else 15000L
                        if (idleTime > timeout && audioQueue.isEmpty()) break
                    }
                } catch (e: Exception) {
                    break
                }
            }
            
            // Cleanup session
            try {
                Thread.sleep(1000) // Hardware tail
                audioTrack?.stop()
                audioTrack?.release()
                audioTrack = null
            } catch (e: Exception) {}
            
            if (isSessionActive) {
                isSessionActive = false
                onSpeakingFinished()
            }
            Log.d(TAG, "Playback thread STOP")
        }.apply { 
            name = "TTS-Playback"
            priority = Thread.MAX_PRIORITY
            start() 
        }
    }

    fun stop() {
        Log.d(TAG, "Full Stop Requested")
        stopPlayback()
        try {
            webSocket?.send(JSONObject().apply { put("type", "Clear") }.toString())
        } catch (e: Exception) {}
    }

    private fun stopPlayback() {
        isSessionActive = false
        isTextFinished = true
        audioQueue.clear()
        pendingTextQueue.clear()
        playbackThread?.interrupt()
        playbackThread = null
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {}
    }

    fun destroy() {
        stop()
        webSocket?.close(1000, null)
    }

    fun getAudioSessionId(): Int = audioTrack?.audioSessionId ?: 0
}
