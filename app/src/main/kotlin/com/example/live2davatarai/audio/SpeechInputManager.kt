package com.example.live2davatarai.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.example.live2davatarai.util.LogUtil
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class SpeechInputManager(
    private val context: Context,
    private val deepgramApiKey: String,
    private val onPartialResult: (String) -> Unit,
    private val onFinalResult: (String) -> Unit,
    private val onError: (Int) -> Unit,
    private val onStateChange: (Boolean) -> Unit
) {
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private val isListening = AtomicBoolean(false)
    private val isSocketOpen = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var lastPartial: String = ""

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val wsUrl =
        "wss://api.deepgram.com/v1/listen" +
            "?model=nova-2" +
            "&language=en-US" +
            "&encoding=linear16" +
            "&sample_rate=$sampleRate" +
            "&channels=1" +
            "&interim_results=true" +
            "&punctuate=true" +
            "&endpointing=300"

    fun startListening() {
        if (!isListening.compareAndSet(false, true)) return
        connect()
    }

    fun stopListening() {
        stopInternal(true)
    }

    private fun stopInternal(notify: Boolean) {
        if (!isListening.compareAndSet(true, false)) return
        isSocketOpen.set(false)
        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        audioThread?.interrupt()
        audioThread = null
        try {
            webSocket?.close(1000, "Client stop")
        } catch (_: Exception) {}
        webSocket = null
        if (notify) onStateChange(false)
    }

    private fun connect() {
        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("Authorization", "Token $deepgramApiKey")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isSocketOpen.set(true)
                onStateChange(true)
                startAudio()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val channel = json.optJSONObject("channel") ?: return
                    val alternatives = channel.optJSONArray("alternatives") ?: return
                    if (alternatives.length() == 0) return
                    val alt = alternatives.optJSONObject(0) ?: return
                    val transcript = alt.optString("transcript", "").trim()
                    if (transcript.isEmpty()) return

                    val isFinal = json.optBoolean("is_final", false)
                    val speechFinal = json.optBoolean("speech_final", false)

                    if (isFinal) {
                        lastPartial = ""
                        onFinalResult(transcript)
                        if (speechFinal) {
                            stopInternal(true)
                        }
                    } else {
                        if (transcript != lastPartial) {
                            lastPartial = transcript
                            onPartialResult(transcript)
                        }
                    }
                } catch (e: Exception) {
                    LogUtil.e("SpeechInputManager", "Parse error: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                LogUtil.e("SpeechInputManager", "WS fail: ${t.message}")
                onError(-1)
                stopInternal(true)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                LogUtil.d("SpeechInputManager", "WS closed: $code $reason")
                stopInternal(true)
            }
        })
    }

    private fun startAudio() {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = (minBuf * 2).coerceAtLeast(4096)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        try {
            audioRecord?.startRecording()
        } catch (e: Exception) {
            LogUtil.e("SpeechInputManager", "Audio start error: ${e.message}")
            onError(-2)
            stopInternal(true)
            return
        }

        audioThread = Thread {
            val buf = ByteArray(bufferSize)
            while (isListening.get()) {
                val read = audioRecord?.read(buf, 0, buf.size) ?: 0
                if (read > 0 && isSocketOpen.get()) {
                    webSocket?.send(buf.toByteString(0, read))
                } else {
                    SystemClock.sleep(10)
                }
            }
        }.apply {
            name = "DG-STT-Audio"
            start()
        }
    }

    fun destroy() {
        stopInternal(false)
    }
}
