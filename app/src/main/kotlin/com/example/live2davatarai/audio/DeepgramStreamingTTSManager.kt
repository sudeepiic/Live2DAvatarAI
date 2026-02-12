package com.example.live2davatarai.audio

import android.content.Context
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import com.example.live2davatarai.util.LogUtil
import java.io.ByteArrayOutputStream
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

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

    private val audioQueue = ArrayBlockingQueue<ByteArray>(256)
    private val pendingTextQueue = ArrayBlockingQueue<String>(50)
    @Volatile private var pendingSpeakText: String? = null
    private val audioBuffer = ByteArrayOutputStream(256 * 1024)
    
    private val isAppAlive = AtomicBoolean(true)
    private val hasNotifiedFinish = AtomicBoolean(false)
    private val lastAudioTimeMs = AtomicLong(0)
    private val lastSpeakTimeMs = AtomicLong(0)
    @Volatile private var pendingFlush = false
    @Volatile private var acceptAudio = false
    @Volatile private var isConnecting = false
    @Volatile private var lastSpeakText: String? = null
    @Volatile private var fallbackTriggered = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var prevAudioMode: Int? = null
    private var prevSpeakerOn: Boolean? = null
    private val sessionCounter = AtomicInteger(0)
    @Volatile private var currentSessionId = 0
    @Volatile private var playedBuffer = false
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
        // Lazy connect: only when needed
    }

    private fun createAudioTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = (minBuf * 4).coerceAtLeast(131072)

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
        if (webSocket != null) {
            if (isConnected || isConnecting) {
                LogUtil.d(TAG, "connect() skipped: connected=$isConnected connecting=$isConnecting")
                return
            }
            LogUtil.d(TAG, "connect() resetting stale socket")
            try { webSocket?.close(1001, "Reset") } catch (_: Exception) {}
            webSocket = null
        }
        if (isConnecting) {
            LogUtil.d(TAG, "connect() skipped: already connecting")
            return
        }
        LogUtil.d(TAG, "connect()")
        isConnecting = true
        
        val request = Request.Builder()
            .url(WS_URL)
            .addHeader("Authorization", "Token $apiKey")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                LogUtil.d(TAG, "WebSocket OPEN")
                isConnected = true
                isConnecting = false
                while (pendingTextQueue.isNotEmpty()) {
                    pendingTextQueue.poll()?.let { sendToWs(it) }
                }
                pendingSpeakText?.let {
                    pendingSpeakText = null
                    sendToWs(it)
                }
                if (pendingFlush) {
                    sendFlush()
                    pendingFlush = false
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!acceptAudio) return
                val data = bytes.toByteArray()
                if (data.isNotEmpty()) {
                    lastAudioTimeMs.set(System.currentTimeMillis())
                    synchronized(audioBuffer) {
                        audioBuffer.write(data)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                LogUtil.d(TAG, "WS text: $text")
                if (!acceptAudio) return
                try {
                    val json = JSONObject(text)
                    if (json.has("audio")) {
                        val b64 = json.optString("audio", "")
                        if (b64.isNotEmpty()) {
                            val data = Base64.decode(b64, Base64.DEFAULT)
                            if (data.isNotEmpty()) {
                                lastAudioTimeMs.set(System.currentTimeMillis())
                                synchronized(audioBuffer) {
                                    audioBuffer.write(data)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // ignore non-audio messages
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                LogUtil.e(TAG, "WebSocket FAIL: ${t.message}")
                isConnected = false
                isConnecting = false
                this@DeepgramStreamingTTSManager.webSocket = null
                finishSessionIfActive()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                LogUtil.d(TAG, "WebSocket CLOSED: $code $reason")
                isConnected = false
                isConnecting = false
                this@DeepgramStreamingTTSManager.webSocket = null
            }
        })
    }

    fun startStream() {
        LogUtil.d(TAG, "--- Session Start ---")
        stopPlayback()
        
        isSessionActive = true
        isTextFinished = false
        hasNotifiedFinish.set(false)
        audioQueue.clear()
        lastAudioTimeMs.set(0)
        lastSpeakTimeMs.set(System.currentTimeMillis())
        pendingFlush = false
        currentSessionId = sessionCounter.incrementAndGet()
        acceptAudio = true
        pendingSpeakText = null
        playedBuffer = false
        synchronized(audioBuffer) {
            audioBuffer.reset()
        }

        // Do not Clear here; it can cut mid-response. We only Clear on explicit stop().

        forceSpeakerRoute(true)
        audioTrack = createAudioTrack()
        
        connect()
        startPlaybackThread()
    }

    fun enqueue(text: String) {
        if (text.isBlank()) return
        val socket = webSocket
        if (!isConnected || socket == null) {
            pendingSpeakText = text
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
            val ok = webSocket?.send(msg.toString()) ?: false
            lastSpeakTimeMs.set(System.currentTimeMillis())
            lastSpeakText = text
            fallbackTriggered = false
            LogUtil.d(TAG, "Speak send ok=$ok len=${text.length}")
            if (!ok) {
                LogUtil.w(TAG, "Speak send failed, queueing")
                offerPendingText(text)
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "Send Error: ${e.message}")
            offerPendingText(text)
        }
    }

    fun endStream() {
        LogUtil.d(TAG, "endStream()")
        isTextFinished = true
        if (isConnected) {
            sendFlush()
        } else {
            pendingFlush = true
        }
        scheduleFallbackIfSilent()
    }

    private fun sendFlush() {
        try {
            val ok = webSocket?.send(JSONObject().apply { put("type", "Flush") }.toString()) ?: false
            if (!ok) {
                LogUtil.w(TAG, "Flush send failed, retry later")
                pendingFlush = true
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "Flush Error: ${e.message}")
            pendingFlush = true
        }
    }

    private fun startPlaybackThread() {
        val sessionId = currentSessionId
        playbackThread = Thread {
            while (isAppAlive.get() && sessionCounter.get() == sessionId) {
                try {
                    val now = System.currentTimeMillis()
                    val lastAudio = lastAudioTimeMs.get()
                    val idleAudio = if (lastAudio == 0L) 0L else (now - lastAudio)
                    val idleSpeak = now - lastSpeakTimeMs.get()
                    val timeout = if (isTextFinished) 8000L else 20000L

                    if (isTextFinished && !playedBuffer && lastAudio > 0 && idleAudio > 200) {
                        val bytes = synchronized(audioBuffer) { audioBuffer.toByteArray() }
                        if (bytes.isNotEmpty()) {
                            try {
                                audioTrack?.play()
                                var offset = 0
                                while (offset < bytes.size) {
                                    val written = audioTrack?.write(bytes, offset, bytes.size - offset) ?: 0
                                    if (written <= 0) break
                                    offset += written
                                }
                            } catch (_: Exception) {}
                            playedBuffer = true
                        }
                    }

                    if (isTextFinished && lastAudio == 0L && idleSpeak > 1500 && !fallbackTriggered && lastSpeakText != null) {
                        fallbackTriggered = true
                        val text = lastSpeakText ?: ""
                        Thread {
                            val bytes = fetchRawAudioFallback(text)
                            if (bytes != null && bytes.isNotEmpty()) {
                                synchronized(audioBuffer) {
                                    audioBuffer.reset()
                                    audioBuffer.write(bytes)
                                }
                                lastAudioTimeMs.set(System.currentTimeMillis())
                                playedBuffer = false
                            } else {
                                LogUtil.w(TAG, "Fallback audio empty")
                            }
                        }.apply {
                            name = "TTS-Fallback"
                            start()
                        }
                    }

                    if (lastAudio > 0 && idleAudio > timeout && idleSpeak > timeout && playedBuffer) break
                    if (isTextFinished && lastAudio == 0L && idleSpeak > timeout) break

                    Thread.sleep(50)
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
            forceSpeakerRoute(false)
            
            if (sessionCounter.get() == sessionId) {
                finishSessionIfActive()
            }
            LogUtil.d(TAG, "Playback thread STOP")
        }.apply { 
            name = "TTS-Playback"
            priority = Thread.NORM_PRIORITY
            start() 
        }
    }

    private fun scheduleFallbackIfSilent() {
        LogUtil.d(TAG, "Fallback scheduled")
        val sessionId = currentSessionId
        val text = lastSpeakText
        if (text.isNullOrBlank()) return
        Thread {
            try { Thread.sleep(1600) } catch (_: Exception) {}
            if (sessionCounter.get() != sessionId) return@Thread
            if (lastAudioTimeMs.get() != 0L) return@Thread
            if (fallbackTriggered) return@Thread
            fallbackTriggered = true
            val bytes = fetchRawAudioFallback(text)
            if (bytes != null && bytes.isNotEmpty()) {
                LogUtil.d(TAG, "Fallback audio bytes=${bytes.size}")
                synchronized(audioBuffer) {
                    audioBuffer.reset()
                    audioBuffer.write(bytes)
                }
                lastAudioTimeMs.set(System.currentTimeMillis())
                playedBuffer = false
            } else {
                LogUtil.w(TAG, "Fallback audio empty")
            }
        }.apply {
            name = "TTS-Fallback-Delay"
            start()
        }
    }

    fun stop() {
        LogUtil.d(TAG, "Full Stop Requested")
        stopPlayback()
        try {
            webSocket?.send(JSONObject().apply { put("type", "Clear") }.toString())
        } catch (e: Exception) {}
    }

    private fun stopPlayback() {
        isSessionActive = false
        isTextFinished = true
        sessionCounter.incrementAndGet() // invalidate any running playback thread
        audioQueue.clear()
        pendingTextQueue.clear()
        pendingSpeakText = null
        acceptAudio = false
        playedBuffer = false
        synchronized(audioBuffer) {
            audioBuffer.reset()
        }
        playbackThread?.interrupt()
        playbackThread = null
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {}
    }

    fun disconnect() {
        isConnected = false
        isConnecting = false
        webSocket?.close(1000, "Disconnect")
        webSocket = null
    }

    fun destroy() {
        isAppAlive.set(false)
        stop()
        disconnect()
    }

    fun getAudioSessionId(): Int = audioTrack?.audioSessionId ?: 0

    private fun offerAudio(data: ByteArray) {
        if (!audioQueue.offer(data)) {
            audioQueue.poll()
            audioQueue.offer(data)
        }
    }

    private fun offerPendingText(text: String) {
        if (!pendingTextQueue.offer(text)) {
            pendingTextQueue.poll()
            pendingTextQueue.offer(text)
        }
    }

    // Single-speak mode: no sentence queue here

    private fun finishSessionIfActive() {
        if (isSessionActive) {
            isSessionActive = false
            if (hasNotifiedFinish.compareAndSet(false, true)) {
                onSpeakingFinished()
            }
        }
    }

    private fun fetchRawAudioFallback(text: String): ByteArray? {
        return try {
            LogUtil.d(TAG, "Fallback fetch start")
            val url = "https://api.deepgram.com/v1/speak?model=$MODEL&encoding=linear16&sample_rate=$SAMPLE_RATE"
            val bodyJson = JSONObject().apply { put("text", text) }.toString()
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Token $apiKey")
                .addHeader("Accept", "audio/x-raw")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    LogUtil.w(TAG, "Fallback HTTP ${resp.code}")
                    return null
                }
                val bytes = resp.body?.bytes() ?: return null
                if (bytes.isEmpty()) return null
                // If Deepgram returns WAV despite accept, strip header
                val contentType = resp.header("Content-Type") ?: ""
                if (contentType.contains("wav", ignoreCase = true)) {
                    stripWavHeader(bytes)
                } else {
                    bytes
                }
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "Fallback error: ${e.message}")
            null
        }
    }

    private fun stripWavHeader(bytes: ByteArray): ByteArray {
        if (bytes.size < 44) return bytes
        var i = 12
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4, Charsets.US_ASCII)
            val size = (bytes[i + 4].toInt() and 0xFF) or
                ((bytes[i + 5].toInt() and 0xFF) shl 8) or
                ((bytes[i + 6].toInt() and 0xFF) shl 16) or
                ((bytes[i + 7].toInt() and 0xFF) shl 24)
            val dataStart = i + 8
            val dataEnd = (dataStart + size).coerceAtMost(bytes.size)
            if (id == "data" && dataStart < dataEnd) {
                return bytes.copyOfRange(dataStart, dataEnd)
            }
            i += 8 + size
        }
        return if (bytes.size > 44) bytes.copyOfRange(44, bytes.size) else bytes
    }

    private fun forceSpeakerRoute(enable: Boolean) {
        try {
            if (enable) {
                if (prevAudioMode == null) prevAudioMode = audioManager.mode
                if (prevSpeakerOn == null) prevSpeakerOn = audioManager.isSpeakerphoneOn
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = true
            } else {
                prevAudioMode?.let { audioManager.mode = it }
                prevSpeakerOn?.let { audioManager.isSpeakerphoneOn = it }
                prevAudioMode = null
                prevSpeakerOn = null
            }
        } catch (_: Exception) {}
    }
}
