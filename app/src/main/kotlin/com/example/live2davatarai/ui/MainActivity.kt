package com.example.live2davatarai.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.live2davatarai.BuildConfig
import com.example.live2davatarai.audio.AudioAnalyzer
import com.example.live2davatarai.audio.SpeechInputManager
import com.example.live2davatarai.audio.DeepgramStreamingTTSManager
import com.example.live2davatarai.data.ConversationManager
import com.example.live2davatarai.databinding.ActivityMainBinding
import com.example.live2davatarai.engine.AvatarController
import com.example.live2davatarai.engine.AvatarExpression
import com.example.live2davatarai.engine.AvatarState
import com.example.live2davatarai.network.OpenAIClient
import com.example.live2davatarai.util.LogUtil
import com.live2d.demo.JniBridgeJava

class MainActivity : AppCompatActivity() {
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var activeRequestId: Long = 0L
    private var lastInteractionMs: Long = 0L
    private var idleStage: Int = 0
    private var idleStage1Ms: Long = 0L
    private var idleStage2Ms: Long = 0L
    private var idleStage3Ms: Long = 0L
    private var idleStage4Ms: Long = 0L
    private val idleRunnable = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val elapsed = now - lastInteractionMs
            if (isListening || avatarController.currentState == AvatarState.SPEAKING || avatarController.currentState == AvatarState.THINKING) {
                mainHandler.postDelayed(this, 1000L)
                return
            }
            when (idleStage) {
                0 -> if (elapsed >= idleStage1Ms) {
                    triggerMotion("Idle")
                    idleStage = 1
                }
                1 -> if (elapsed >= idleStage2Ms) {
                    triggerMotion("Walk")
                    idleStage = 2
                }
                2 -> if (elapsed >= idleStage3Ms) {
                    triggerMotion("Jump")
                    idleStage = 3
                }
                3 -> if (elapsed >= idleStage4Ms) {
                    triggerExpression("ACC 2 [BRB]")
                    idleStage = 4
                }
            }
            mainHandler.postDelayed(this, 1000L)
        }
    }
    private lateinit var binding: ActivityMainBinding
    private var speechInputManager: SpeechInputManager? = null
    private var ttsManager: DeepgramStreamingTTSManager? = null
    private var aiClient: OpenAIClient? = null
    private lateinit var conversationManager: ConversationManager
    private lateinit var avatarController: AvatarController
    private var audioAnalyzer: AudioAnalyzer? = null

    private val PERMISSION_REQUEST_CODE = 100
    private var isListening = false

    private val deepgramApiKey = BuildConfig.DEEPGRAM_API_KEY
    private val openAiApiKey = BuildConfig.OPENAI_API_KEY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        try {
            JniBridgeJava.SetContext(this)
            JniBridgeJava.SetActivityInstance(this)
        } catch (e: Exception) {
            LogUtil.e("MainActivity", "JNI Setup Failed", e)
        }

        avatarController = AvatarController()
        binding.avatarSurfaceView.setController(avatarController)
        conversationManager = ConversationManager()
        aiClient = OpenAIClient(openAiApiKey)

        setupListeners()
        checkPermissionsAndInit()
        
        binding.micButton.visibility = android.view.View.GONE
        binding.settingsButton.visibility = android.view.View.GONE
        binding.avatarSurfaceView.setOnClickListener { toggleListening() }

    }

    private fun toggleListening() {
        if (speechInputManager == null) {
            checkPermissionsAndInit()
            return
        }
        if (isListening) {
            speechInputManager?.stopListening()
        } else {
            // Stop any ongoing speech immediately to prevent self-hearing (bleeding)
            ttsManager?.stop()

            ttsManager?.warmConnect()
            speechInputManager?.startListening()
            avatarController.updateState(AvatarState.LISTENING)
            binding.statusText.text = "Listening..."
        }
    }

    private fun checkPermissionsAndInit() {
        val permissions = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.MODIFY_AUDIO_SETTINGS)
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) initPermissionDependentModules()
        else ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    private fun initPermissionDependentModules() {
        if (ttsManager != null) return
        try {
            ttsManager = DeepgramStreamingTTSManager(this, deepgramApiKey) {
                runOnUiThread {
                    avatarController.updateState(AvatarState.IDLE)
                    binding.statusText.text = "Idle"
                }
            }
            ttsManager?.warmConnect()
            audioAnalyzer = AudioAnalyzer { amplitude -> avatarController.setSpeechAmplitude(amplitude) }
            audioAnalyzer?.start(ttsManager?.getAudioSessionId() ?: 0)
            
            speechInputManager = SpeechInputManager(this, deepgramApiKey,
                onPartialResult = { partial -> runOnUiThread { binding.statusText.text = "Listening: $partial" } },
                onFinalResult = { final ->
                    // Stop TTS when user speaks
                    ttsManager?.stop()
                    handleUserInput(final)
                },
                onError = { error -> runOnUiThread { 
                    binding.statusText.text = "Error: $error"
                    avatarController.updateState(AvatarState.IDLE)
                }},
                onStateChange = { active -> runOnUiThread { 
                    isListening = active
                    updateUIState() 
                }}
            )
        } catch (e: Exception) {
            LogUtil.e("MainActivity", "Module Init Failed", e)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            initPermissionDependentModules()
        }
    }

    private fun setupListeners() {
        binding.settingsButton.setOnClickListener {
            Toast.makeText(this, "Using Deepgram TTS + OpenAI API", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetIdleSchedule() {
        val rand = kotlin.random.Random
        lastInteractionMs = SystemClock.uptimeMillis()
        idleStage = 0
        idleStage1Ms = rand.nextLong(5000L, 15001L)
        idleStage2Ms = rand.nextLong(20000L, 40001L)
        idleStage3Ms = rand.nextLong(120000L, 180001L)
        idleStage4Ms = rand.nextLong(180000L, 300001L)
    }

    private fun triggerMotion(group: String, priority: Int = 2) {
        if (JniBridgeJava.isReady()) {
            try { JniBridgeJava.nativeStartMotion(group, priority) } catch (_: Throwable) {}
        }
    }

    private fun triggerExpression(name: String) {
        if (JniBridgeJava.isReady()) {
            try { JniBridgeJava.nativeSetExpression(name) } catch (_: Throwable) {}
        }
    }

    private fun handleUserInput(input: String) {
        if (input.isBlank()) return
        LogUtil.d("MainActivity", "handleUserInput()")
        resetIdleSchedule()
        runOnUiThread {
            binding.statusText.text = "Heard: ${input.take(80)}"
            avatarController.updateState(AvatarState.THINKING)
        }
        conversationManager.addUserMessage(input)

        if (aiClient == null) {
            runOnUiThread {
                Toast.makeText(this, "AI client not initialized", Toast.LENGTH_SHORT).show()
                avatarController.updateState(AvatarState.IDLE)
                binding.statusText.text = "Idle"
            }
            return
        }

        val requestId = SystemClock.uptimeMillis()
        activeRequestId = requestId
        val fullResponse = StringBuilder()
        var isTagFound = false
        val speakBuffer = StringBuilder()
        val displayBuffer = StringBuilder()
        var inTag = false
        var gotAnyToken = false
        var lastTokenTimeMs = SystemClock.uptimeMillis()
        var lastUiUpdateMs = 0L
        var firstChunkSent = false

        // CRITICAL: Force state to SPEAKING immediately and don't let it flicker
        runOnUiThread {
            avatarController.updateState(AvatarState.SPEAKING)
            binding.statusText.text = "Thinking..."
        }
        ttsManager?.startStream()
        
        // Update analyzer with the fresh session ID (since AudioTrack is recreated)
        audioAnalyzer?.stop()
        audioAnalyzer?.start(ttsManager?.getAudioSessionId() ?: 0)

        LogUtil.d("MainActivity", "Starting AI stream")
        val watchdog = object : Runnable {
            override fun run() {
                if (activeRequestId != requestId) return
                val now = SystemClock.uptimeMillis()
                val idleMs = now - lastTokenTimeMs
                if (!gotAnyToken && idleMs > 6000L) {
                    LogUtil.w("MainActivity", "AI stream timeout: no tokens")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "AI timeout", Toast.LENGTH_SHORT).show()
                        avatarController.updateState(AvatarState.IDLE)
                        binding.statusText.text = "Idle"
                    }
                    ttsManager?.endStream()
                    activeRequestId = 0L
                    return
                }
                if (gotAnyToken && idleMs > 8000L) {
                    LogUtil.w("MainActivity", "AI stream stalled: no new tokens")
                    runOnUiThread {
                        avatarController.updateState(AvatarState.IDLE)
                        binding.statusText.text = "Idle"
                    }
                    ttsManager?.endStream()
                    activeRequestId = 0L
                    return
                }
                mainHandler.postDelayed(this, 1000L)
            }
        }
        mainHandler.postDelayed(watchdog, 1000L)
        mainHandler.postDelayed({
            if (activeRequestId != requestId) return@postDelayed
            if (!firstChunkSent && !gotAnyToken) {
                // Pre-seed to mask initial silence on slow token starts.
                ttsManager?.enqueue("hmm...")
                firstChunkSent = true
            }
        }, 350L)

        aiClient?.streamResponse(
            systemInstruction = conversationManager.systemInstruction,
            history = conversationManager.getHistoryJson(),
            onTokenReceived = { token ->
                gotAnyToken = true
                lastTokenTimeMs = SystemClock.uptimeMillis()
                fullResponse.append(token)
                parseAndTriggerTags(token)
                // Stream-safe tag filtering: ignore text between [ and ]
                for (ch in token) {
                    when {
                        ch == '[' -> inTag = true
                        ch == ']' -> inTag = false
                        !inTag -> {
                            speakBuffer.append(ch)
                            displayBuffer.append(ch)
                        }
                    }
                }

                if (!isTagFound && fullResponse.contains("[") && fullResponse.contains("]")) {
                    val start = fullResponse.indexOf("[")
                    val end = fullResponse.indexOf("]")
                    val tag = fullResponse.substring(start + 1, end).uppercase()
                    try {
                        val expression = AvatarExpression.valueOf(tag)
                        runOnUiThread { avatarController.setExpression(expression) }
                    } catch (e: Exception) {}
                    isTagFound = true
                }

                // Stream chunks to TTS with low latency but avoid micro-chunks
                val buf = speakBuffer.toString()
                val hasSentenceEnd = buf.contains('.') || buf.contains('!') || buf.contains('?') || buf.contains('\n')
                if (!firstChunkSent && (hasSentenceEnd || buf.length >= 4)) {
                    val chunk = buf.trim()
                    if (chunk.isNotEmpty()) {
                        ttsManager?.enqueue(chunk)
                        firstChunkSent = true
                    }
                    speakBuffer.clear()
                } else if ((hasSentenceEnd && buf.length >= 20) || buf.length >= 120) {
                    val chunk = buf.trim()
                    if (chunk.isNotEmpty()) {
                        ttsManager?.enqueue(chunk)
                    }
                    speakBuffer.clear()
                }

                val now = SystemClock.uptimeMillis()
                if (now - lastUiUpdateMs > 120L) {
                    val preview = displayBuffer.toString()
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .takeLast(120)
                    if (preview.isNotEmpty()) {
                        runOnUiThread { binding.statusText.text = preview }
                        lastUiUpdateMs = now
                    }
                }
            },
            onComplete = {
                LogUtil.d("MainActivity", "AI stream complete")
                activeRequestId = 0L
                resetIdleSchedule()
                val remaining = speakBuffer.toString().trim()
                    .replace(Regex("\\[.*?\\]"), "")
                    .replace(Regex("\\*.*?\\*"), "")
                    .trim()
                
                runOnUiThread {
                    val finalPreview = displayBuffer.toString()
                        .replace(Regex("\\s+"), " ")
                        .trim()
                    if (finalPreview.isNotEmpty()) {
                        binding.statusText.text = finalPreview.takeLast(200)
                    }
                    if (remaining.isNotEmpty()) {
                        ttsManager?.enqueue(remaining)
                    }
                    // ONLY signal end when the AI is completely finished AND final sentence is queued
                    ttsManager?.endStream()
                }
                conversationManager.addModelMessage(fullResponse.toString())
            },
            onError = { error ->
                activeRequestId = 0L
                resetIdleSchedule()
                runOnUiThread {
                    Toast.makeText(this, "AI Error: ${error.message}", Toast.LENGTH_SHORT).show()
                    avatarController.updateState(AvatarState.IDLE)
                    binding.statusText.text = "Idle"
                }
            }
        )
    }

    private fun parseAndTriggerTags(token: String) {
        val motionMatch = Regex("\\[MOTION:([A-Z_]+)]").find(token)
        if (motionMatch != null) {
            when (motionMatch.groupValues[1]) {
                "DANCE" -> triggerMotion("Dance")
                "WALK" -> triggerMotion("Walk")
                "JUMP" -> triggerMotion("Jump")
                "IDLE" -> triggerMotion("Idle")
            }
        }
        val exprMatch = Regex("\\[EXPR:([^\\]]+)]").find(token)
        if (exprMatch != null) {
            triggerExpression(exprMatch.groupValues[1].trim())
        }
    }

    private fun updateUIState() {
        if (isListening) {
            binding.micButton.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        } else {
            binding.micButton.setImageResource(android.R.drawable.ic_btn_speak_now)
        }
    }

    override fun onStart() {
        super.onStart()
        try { if (JniBridgeJava.isReady()) JniBridgeJava.nativeOnStart() } catch (t: Throwable) {}
        ttsManager?.warmConnect()
        resetIdleSchedule()
        mainHandler.post(idleRunnable)
    }

    override fun onPause() {
        super.onPause()
        try { if (JniBridgeJava.isReady()) JniBridgeJava.nativeOnPause() } catch (t: Throwable) {}
        ttsManager?.stop()
    }

    override fun onStop() {
        super.onStop()
        try { if (JniBridgeJava.isReady()) JniBridgeJava.nativeOnStop() } catch (t: Throwable) {}
        ttsManager?.disconnect()
        mainHandler.removeCallbacks(idleRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { if (JniBridgeJava.isReady()) JniBridgeJava.nativeOnDestroy() } catch (t: Throwable) {}
        speechInputManager?.destroy()
        ttsManager?.destroy()
        audioAnalyzer?.stop()
    }
}
