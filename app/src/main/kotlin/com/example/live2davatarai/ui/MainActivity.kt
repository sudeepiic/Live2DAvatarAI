package com.example.live2davatarai.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.live2davatarai.audio.AudioAnalyzer
import com.example.live2davatarai.audio.SpeechInputManager
import com.example.live2davatarai.audio.TTSManager
import com.example.live2davatarai.data.ConversationManager
import com.example.live2davatarai.databinding.ActivityMainBinding
import com.example.live2davatarai.engine.AvatarController
import com.example.live2davatarai.engine.AvatarExpression
import com.example.live2davatarai.engine.AvatarState
import com.example.live2davatarai.network.GeminiClient
import com.live2d.demo.JniBridgeJava

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var speechInputManager: SpeechInputManager? = null
    private var ttsManager: TTSManager? = null
    private var geminiClient: GeminiClient? = null
    private lateinit var conversationManager: ConversationManager
    private lateinit var avatarController: AvatarController
    private var audioAnalyzer: AudioAnalyzer? = null

    private val PERMISSION_REQUEST_CODE = 100
    private var isListening = false
    
    private val API_KEY = "YOUR_API_KEY_HERE"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        try {
            JniBridgeJava.SetContext(this)
            JniBridgeJava.SetActivityInstance(this)
        } catch (e: Exception) {
            Log.e("MainActivity", "JNI Setup Failed", e)
        }

        avatarController = AvatarController()
        binding.avatarSurfaceView.setController(avatarController)
        conversationManager = ConversationManager()
        geminiClient = GeminiClient(API_KEY)

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
            ttsManager?.stop()
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
            audioAnalyzer = AudioAnalyzer { amplitude -> avatarController.setSpeechAmplitude(amplitude) }
            audioAnalyzer?.start()
            ttsManager = TTSManager(this) {
                runOnUiThread {
                    avatarController.updateState(AvatarState.IDLE)
                    binding.statusText.text = "Idle"
                }
            }
            speechInputManager = SpeechInputManager(this,
                onPartialResult = { partial -> runOnUiThread { binding.statusText.text = "Listening: $partial" } },
                onFinalResult = { final -> handleUserInput(final) },
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
            Log.e("MainActivity", "Module Init Failed", e)
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
            Toast.makeText(this, "API Key is hardcoded.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleUserInput(input: String) {
        if (input.isBlank()) return
        runOnUiThread {
            binding.statusText.text = "Thinking..."
            avatarController.updateState(AvatarState.THINKING)
        }
        conversationManager.addUserMessage(input)

        val fullResponse = StringBuilder()
        var sentenceBuffer = StringBuilder()
        var isTagFound = false

        geminiClient?.streamResponse(
            systemInstruction = conversationManager.systemInstruction,
            history = conversationManager.getHistoryJson(),
            onTokenReceived = { token ->
                fullResponse.append(token)
                if (fullResponse.startsWith("[") && fullResponse.contains("]")) {
                    val tagEnd = fullResponse.indexOf("]")
                    if (!isTagFound) {
                        val tag = fullResponse.substring(1, tagEnd).uppercase()
                        try {
                            val expression = AvatarExpression.valueOf(tag)
                            runOnUiThread { avatarController.setExpression(expression) }
                        } catch (e: Exception) {}
                        isTagFound = true
                    }
                    if (token.contains("]")) {
                        sentenceBuffer.append(token.substring(token.indexOf("]") + 1))
                    } else if (isTagFound) {
                        sentenceBuffer.append(token)
                    }
                } else if (!fullResponse.startsWith("[")) {
                    sentenceBuffer.append(token)
                }
                
                if (token.contains(".") || token.contains("!") || token.contains("?")) {
                    val sentence = sentenceBuffer.toString().trim().replace(Regex("\\[.*?\\]"), "").replace(Regex("\\*.*?\\*"), "")
                    if (sentence.isNotEmpty()) {
                        runOnUiThread {
                            binding.statusText.text = "Speaking..."
                            if (avatarController.currentState != AvatarState.SPEAKING) {
                                avatarController.updateState(AvatarState.SPEAKING)
                            }
                            ttsManager?.speak(sentence)
                        }
                        sentenceBuffer = StringBuilder()
                    }
                }
            },
            onComplete = {
                val remaining = sentenceBuffer.toString().trim().replace(Regex("\\[.*?\\]"), "").replace(Regex("\\*.*?\\*"), "")
                if (remaining.isNotEmpty()) {
                    runOnUiThread { 
                        binding.statusText.text = "Speaking..."
                        ttsManager?.speak(remaining)
                    }
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
    }

    override fun onStop() {
        super.onStop()
        try { if (JniBridgeJava.isReady()) JniBridgeJava.nativeOnStop() } catch (t: Throwable) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try { if (JniBridgeJava.isReady()) JniBridgeJava.nativeOnDestroy() } catch (t: Throwable) {}
        speechInputManager?.destroy()
        ttsManager?.destroy()
        audioAnalyzer?.stop()
    }
}
