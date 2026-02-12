package com.example.live2davatarai.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
    private lateinit var binding: ActivityMainBinding
    private var speechInputManager: SpeechInputManager? = null
    private var ttsManager: DeepgramStreamingTTSManager? = null
    private var aiClient: OpenAIClient? = null
    private lateinit var conversationManager: ConversationManager
    private lateinit var avatarController: AvatarController
    private var audioAnalyzer: AudioAnalyzer? = null

    private val PERMISSION_REQUEST_CODE = 100
    private var isListening = false

    private val deepgramApiKey = "REDACTED"
    private val openAiApiKey = "REDACTED"

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
            
            // Pre-warm the TTS connection
            ttsManager?.connect()
            
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
            audioAnalyzer = AudioAnalyzer { amplitude -> avatarController.setSpeechAmplitude(amplitude) }
            audioAnalyzer?.start(ttsManager?.getAudioSessionId() ?: 0)
            
            speechInputManager = SpeechInputManager(this,
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

    private fun handleUserInput(input: String) {
        if (input.isBlank()) return
        LogUtil.d("MainActivity", "handleUserInput()")
        runOnUiThread {
            binding.statusText.text = "Heard: ${input.take(80)}"
            avatarController.updateState(AvatarState.THINKING)
        }
        conversationManager.addUserMessage(input)

        val fullResponse = StringBuilder()
        var isTagFound = false

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
        aiClient?.streamResponse(
            systemInstruction = conversationManager.systemInstruction,
            history = conversationManager.getHistoryJson(),
            onTokenReceived = { token ->
                fullResponse.append(token)
                
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

            },
            onComplete = {
                LogUtil.d("MainActivity", "AI stream complete")
                val remaining = fullResponse.toString().trim()
                    .replace(Regex("\\[.*?\\]"), "")
                    .replace(Regex("\\*.*?\\*"), "")
                    .trim()
                
                runOnUiThread {
                    if (remaining.isNotEmpty()) {
                        binding.statusText.text = "Speaking: $remaining"
                        ttsManager?.enqueue(remaining)
                    }
                    // ONLY signal end when the AI is completely finished AND final sentence is queued
                    ttsManager?.endStream()
                }
                conversationManager.addModelMessage(fullResponse.toString())
            },
            onError = { error ->
                runOnUiThread {
                    Toast.makeText(this, "AI Error: ${error.message}", Toast.LENGTH_SHORT).show()
                    avatarController.updateState(AvatarState.IDLE)
                    binding.statusText.text = "Idle"
                }
            }
        )
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
    }

    override fun onDestroy() {
        super.onDestroy()
        try { if (JniBridgeJava.isReady()) JniBridgeJava.nativeOnDestroy() } catch (t: Throwable) {}
        speechInputManager?.destroy()
        ttsManager?.destroy()
        audioAnalyzer?.stop()
    }
}
