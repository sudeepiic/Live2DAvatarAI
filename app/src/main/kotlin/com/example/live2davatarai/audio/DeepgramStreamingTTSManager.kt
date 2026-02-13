package com.example.live2davatarai.audio

import android.content.Context
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.os.Build
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.AudioDeviceInfo
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
    private var prevStreamVolume: Int? = null
    private var focusRequest: AudioFocusRequest? = null
    private val totalAudioBytes = AtomicLong(0)
    private val sessionCounter = AtomicInteger(0)
    @Volatile private var currentSessionId = 0
    @Volatile private var playedBuffer = false
    @Volatile private var isSessionActive = false
    @Volatile private var isTextFinished = false
    @Volatile private var isConnected = false
    @Volatile private var receivedFlush = false
    @Volatile private var firstChunkPlayed = false
    @Volatile private var pendingClose = false
    @Volatile private var playbackThread: Thread? = null
    private val bufferedBytes = AtomicLong(0)
    private val tFirstAudioMs = AtomicLong(0)
    private val tFirstPlayMs = AtomicLong(0)
    private val tEndStreamMs = AtomicLong(0)

    companion object {
        private const val TAG = "DeepgramTTS"
        private const val SAMPLE_RATE = 24000
        private const val MODEL = "aura-asteria-en"
        private const val WS_URL = "wss://api.deepgram.com/v1/speak?model=$MODEL&encoding=linear16&sample_rate=$SAMPLE_RATE&container=none"
        private const val STREAMING_PLAYBACK = true
    }

    init {
        // Lazy connect: only when needed
    }

    private fun createAudioTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = (minBuf * 4).coerceAtLeast(131072)

        val track = AudioTrack.Builder()
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
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speaker != null) {
                val ok = track.setPreferredDevice(speaker)
                LogUtil.d(TAG, "Preferred speaker device set=$ok")
            } else {
                LogUtil.d(TAG, "Preferred speaker device not found")
            }
        }
        
        return track
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
                if (isSessionActive && STREAMING_PLAYBACK) {
                    forceSpeakerRoute(true)
                    if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                        try { audioTrack?.release() } catch (_: Exception) {}
                        audioTrack = createAudioTrack()
                    }
                    audioTrack?.play()
                }
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
                    if (tFirstAudioMs.compareAndSet(0, System.currentTimeMillis())) {
                        val end = tEndStreamMs.get()
                        if (end > 0) {
                            LogUtil.d(TAG, "Latency: endStream->firstAudio=${tFirstAudioMs.get() - end}ms")
                        }
                    }
                    totalAudioBytes.addAndGet(data.size.toLong())
                    LogUtil.d(TAG, "WS audio bytes=${data.size} total=${totalAudioBytes.get()}")
                    if (STREAMING_PLAYBACK) {
                        if (!firstChunkPlayed) {
                            forceSpeakerRoute(true)
                            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                                try { audioTrack?.release() } catch (_: Exception) {}
                                audioTrack = createAudioTrack()
                            }
                            audioTrack?.play()
                            firstChunkPlayed = true
                            if (tFirstPlayMs.compareAndSet(0, System.currentTimeMillis())) {
                                val end = tEndStreamMs.get()
                                if (end > 0) {
                                    LogUtil.d(TAG, "Latency: endStream->firstPlay=${tFirstPlayMs.get() - end}ms")
                                }
                            }
                        }
                        offerAudio(data)
                    } else {
                        synchronized(audioBuffer) {
                            audioBuffer.write(data)
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                LogUtil.d(TAG, "WS text: $text")
                if (!acceptAudio) return
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type")
                    if (type == "Flushed") {
                        receivedFlush = true
                        if (pendingClose && STREAMING_PLAYBACK) {
                            LogUtil.d(TAG, "Flush received, will close after drain")
                        }
                    }
                    if (json.has("audio")) {
                        val b64 = json.optString("audio", "")
                        if (b64.isNotEmpty()) {
                            val data = Base64.decode(b64, Base64.DEFAULT)
                            if (data.isNotEmpty()) {
                                lastAudioTimeMs.set(System.currentTimeMillis())
                                if (tFirstAudioMs.compareAndSet(0, System.currentTimeMillis())) {
                                    val end = tEndStreamMs.get()
                                    if (end > 0) {
                                        LogUtil.d(TAG, "Latency: endStream->firstAudio=${tFirstAudioMs.get() - end}ms")
                                    }
                                }
                                totalAudioBytes.addAndGet(data.size.toLong())
                                LogUtil.d(TAG, "WS audio(b64) bytes=${data.size} total=${totalAudioBytes.get()}")
                                if (STREAMING_PLAYBACK) {
                                    if (!firstChunkPlayed) {
                                        forceSpeakerRoute(true)
                                        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                                            try { audioTrack?.release() } catch (_: Exception) {}
                                            audioTrack = createAudioTrack()
                                        }
                                        audioTrack?.play()
                                        firstChunkPlayed = true
                                        if (tFirstPlayMs.compareAndSet(0, System.currentTimeMillis())) {
                                            val end = tEndStreamMs.get()
                                            if (end > 0) {
                                                LogUtil.d(TAG, "Latency: endStream->firstPlay=${tFirstPlayMs.get() - end}ms")
                                            }
                                        }
                                    }
                                    offerAudio(data)
                                } else {
                                    synchronized(audioBuffer) {
                                        audioBuffer.write(data)
                                    }
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
        // Best practice: one websocket per conversation
        disconnect()
        
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
        receivedFlush = false
        totalAudioBytes.set(0)
        bufferedBytes.set(0)
        firstChunkPlayed = false
        pendingClose = false
        tFirstAudioMs.set(0)
        tFirstPlayMs.set(0)
        tEndStreamMs.set(0)
        synchronized(audioBuffer) {
            audioBuffer.reset()
        }

        // Do not Clear here; it can cut mid-response. We only Clear on explicit stop().

        forceSpeakerRoute(true)
        audioTrack = createAudioTrack()
        // Pre-roll a tiny silence to open the audio pipeline and avoid first-utterance drop.
        if (STREAMING_PLAYBACK && audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
            try {
                audioTrack?.play()
                val preRollSamples = SAMPLE_RATE / 50 // 20ms
                val preRoll = ByteArray(preRollSamples * 2)
                audioTrack?.write(preRoll, 0, preRoll.size)
            } catch (_: Exception) {}
        }
        
        connect()
        startPlaybackThread()
    }

    fun enqueue(text: String) {
        if (text.isBlank()) return
        if (isSessionActive) {
            // startStream() owns the connection; just queue until onOpen
            pendingSpeakText = text
            return
        }
        val socket = webSocket
        if (!isConnected || socket == null) {
            pendingSpeakText = text
            if (!isConnecting && webSocket == null) connect()
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
                pendingSpeakText = text
                if (!isConnecting && webSocket == null) connect()
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "Send Error: ${e.message}")
            offerPendingText(text)
            pendingSpeakText = text
            if (!isConnecting && webSocket == null) connect()
        }
    }

    fun endStream() {
        LogUtil.d(TAG, "endStream()")
        isTextFinished = true
        tEndStreamMs.compareAndSet(0, System.currentTimeMillis())
        pendingClose = true
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
                if (!isConnecting && webSocket == null) connect()
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "Flush Error: ${e.message}")
            pendingFlush = true
            if (!isConnecting && webSocket == null) connect()
        }
    }

    private fun sendClose() {
        try {
            webSocket?.send(JSONObject().apply { put("type", "Close") }.toString())
        } catch (_: Exception) {}
        try {
            webSocket?.close(1000, "Done")
        } catch (_: Exception) {}
        isConnected = false
        isConnecting = false
        webSocket = null
        LogUtil.d(TAG, "WebSocket close sent")
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

                    if (STREAMING_PLAYBACK) {
                        val chunk = audioQueue.poll(10, TimeUnit.MILLISECONDS)
                        if (chunk != null) {
                            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                                LogUtil.w(TAG, "AudioTrack not initialized, recreating")
                                try { audioTrack?.release() } catch (_: Exception) {}
                                audioTrack = createAudioTrack()
                            }
                            if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                forceSpeakerRoute(true)
                                audioTrack?.play()
                                LogUtil.d(TAG, "Streaming play()")
                            }
                            var written = audioTrack?.write(chunk, 0, chunk.size) ?: 0
                            if (written <= 0) {
                                LogUtil.w(TAG, "Streaming write failed, retrying track")
                                try { audioTrack?.pause() } catch (_: Exception) {}
                                try { audioTrack?.flush() } catch (_: Exception) {}
                                try { audioTrack?.release() } catch (_: Exception) {}
                                audioTrack = createAudioTrack()
                                forceSpeakerRoute(true)
                                audioTrack?.play()
                                written = audioTrack?.write(chunk, 0, chunk.size) ?: 0
                            }
                            if (written > 0) {
                                bufferedBytes.addAndGet(-written.toLong())
                                LogUtil.d(TAG, "Streaming write=${written} buffered=${bufferedBytes.get()}")
                            }
                        }
                    } else {
                        if (isTextFinished && !playedBuffer && lastAudio > 0 && receivedFlush && idleAudio > 800) {
                            val bytes = synchronized(audioBuffer) { audioBuffer.toByteArray() }
                            if (bytes.isNotEmpty()) {
                                try {
                                    val stats = pcmStats(bytes)
                                    LogUtil.d(TAG, "Playback start bytes=${bytes.size} durMs=${stats.durationMs} max=${stats.maxAbs} rms=${stats.rms} state=${audioTrack?.state} playState=${audioTrack?.playState}")
                                    forceSpeakerRoute(true)
                                    if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                                        LogUtil.w(TAG, "AudioTrack not initialized, recreating")
                                        try { audioTrack?.release() } catch (_: Exception) {}
                                        audioTrack = createAudioTrack()
                                    }
                                    var offset = 0
                                    var attempts = 0
                                    while (attempts < 2) {
                                        audioTrack?.play()
                                        try { Thread.sleep(150) } catch (_: Exception) {}
                                        while (offset < bytes.size) {
                                            val written = audioTrack?.write(bytes, offset, bytes.size - offset) ?: 0
                                            LogUtil.d(TAG, "Playback write=${written}")
                                            if (written <= 0) break
                                            offset += written
                                        }
                                        if (offset >= bytes.size) break
                                        attempts += 1
                                        LogUtil.w(TAG, "Playback retry attempt=$attempts offset=$offset")
                                        try { audioTrack?.pause() } catch (_: Exception) {}
                                        try { audioTrack?.flush() } catch (_: Exception) {}
                                        try { audioTrack?.release() } catch (_: Exception) {}
                                        audioTrack = createAudioTrack()
                                    }
                                } catch (_: Exception) {}
                                playedBuffer = true
                            }
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

                    val shouldEndStreaming = STREAMING_PLAYBACK && isTextFinished && receivedFlush && audioQueue.isEmpty() && idleAudio > 500 && totalAudioBytes.get() > 0
                    if (lastAudio > 0 && idleAudio > timeout && idleSpeak > timeout && (playedBuffer || shouldEndStreaming)) break
                    if (isTextFinished && lastAudio == 0L && idleSpeak > timeout) break
                    if (STREAMING_PLAYBACK && pendingClose && receivedFlush && audioQueue.isEmpty() && idleAudio > 500) {
                        sendClose()
                        pendingClose = false
                    }

                    Thread.sleep(5)
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
            LogUtil.d(TAG, "Fallback check: sessionId=$sessionId current=${sessionCounter.get()} lastAudio=${lastAudioTimeMs.get()} triggered=$fallbackTriggered")
            if (sessionCounter.get() != sessionId) {
                LogUtil.d(TAG, "Fallback abort: session changed")
                return@Thread
            }
            if (lastAudioTimeMs.get() != 0L) {
                LogUtil.d(TAG, "Fallback abort: audio already arrived")
                return@Thread
            }
            if (fallbackTriggered) {
                LogUtil.d(TAG, "Fallback abort: already triggered")
                return@Thread
            }
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
        bufferedBytes.addAndGet(data.size.toLong())
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
                if (prevStreamVolume == null) {
                    prevStreamVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                }
                audioManager.mode = AudioManager.MODE_NORMAL
                try {
                    audioManager.isBluetoothScoOn = false
                    audioManager.stopBluetoothSco()
                } catch (_: Exception) {}
                audioManager.isSpeakerphoneOn = true
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .build()
                    focusRequest = req
                    audioManager.requestAudioFocus(req)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                }
            } else {
                prevAudioMode?.let { audioManager.mode = it }
                prevSpeakerOn?.let { audioManager.isSpeakerphoneOn = it }
                prevStreamVolume?.let { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.abandonAudioFocus(null)
                }
                prevAudioMode = null
                prevSpeakerOn = null
                prevStreamVolume = null
                focusRequest = null
            }
        } catch (_: Exception) {}
    }

    private data class PcmStats(val maxAbs: Int, val rms: Int, val durationMs: Long)

    private fun pcmStats(bytes: ByteArray): PcmStats {
        if (bytes.size < 2) return PcmStats(0, 0, 0)
        var maxAbs = 0
        var sumSquares = 0.0
        val sampleCount = bytes.size / 2
        var i = 0
        while (i + 1 < bytes.size) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val abs = kotlin.math.abs(sample)
            if (abs > maxAbs) maxAbs = abs
            sumSquares += (sample * sample).toDouble()
            i += 2
        }
        val rms = kotlin.math.sqrt(sumSquares / sampleCount).toInt()
        val durationMs = (sampleCount * 1000L) / SAMPLE_RATE
        return PcmStats(maxAbs, rms, durationMs)
    }

    // streaming playback uses raw PCM bytes; no gain or diagnostic tone
}
