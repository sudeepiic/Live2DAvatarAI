package com.example.live2davatarai.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var speechInputManager: SpeechInputManager
    private lateinit var ttsManager: TTSManager
    private var geminiClient: GeminiClient? = null
    private lateinit var conversationManager: ConversationManager
    private lateinit var avatarController: AvatarController
    private lateinit var audioAnalyzer: AudioAnalyzer

    private val PERMISSION_REQUEST_CODE = 100
    private var isListening = false
    private var apiKey: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadApiKey()
        initModules()
        setupListeners()
        checkPermissions()
        audioAnalyzer.start()

        if (apiKey.isEmpty()) {
            showApiKeyDialog()
        }
    }

    private fun loadApiKey() {
        val prefs = getSharedPreferences("Live2DAvatarPrefs", Context.MODE_PRIVATE)
        apiKey = prefs.getString("GEMINI_API_KEY", "") ?: ""
    }

    private fun saveApiKey(key: String) {
        val prefs = getSharedPreferences("Live2DAvatarPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("GEMINI_API_KEY", key).apply()
        apiKey = key
        geminiClient = GeminiClient(apiKey)
    }

    private fun showApiKeyDialog() {
        val input = EditText(this)
        input.hint = "Enter Gemini API Key"
        input.setText(apiKey)

        AlertDialog.Builder(this)
            .setTitle("API Key Required")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newKey = input.text.toString().trim()
                if (newKey.isNotEmpty()) {
                    saveApiKey(newKey)
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun initModules() {
        avatarController = AvatarController()
        binding.avatarSurfaceView.setController(avatarController)
        conversationManager = ConversationManager()
        if (apiKey.isNotEmpty()) {
            geminiClient = GeminiClient(apiKey)
        }
        
        audioAnalyzer = AudioAnalyzer { amplitude ->
            avatarController.setSpeechAmplitude(amplitude)
        }

        ttsManager = TTSManager(this) {
            runOnUiThread {
                avatarController.updateState(AvatarState.IDLE)
                binding.statusText.text = "Idle"
            }
        }

        speechInputManager = SpeechInputManager(
            this,
            onPartialResult = { partial ->
                binding.statusText.text = "Listening: $partial"
            },
            onFinalResult = { final ->
                handleUserInput(final)
            },
            onError = { error ->
                binding.statusText.text = "Error: $error"
                avatarController.updateState(AvatarState.IDLE)
            },
            onStateChange = { active ->
                isListening = active
                updateUIState()
            }
        )
    }

    private fun setupListeners() {
        binding.micButton.setOnClickListener {
            if (geminiClient == null) {
                showApiKeyDialog()
                return@setOnClickListener
            }
            if (isListening) {
                speechInputManager.stopListening()
            } else {
                ttsManager.stop()
                speechInputManager.startListening()
                avatarController.updateState(AvatarState.LISTENING)
                binding.statusText.text = "Listening..."
            }
        }
        
        binding.settingsButton.setOnClickListener {
            showApiKeyDialog()
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

        geminiClient?.streamResponse(
            systemInstruction = conversationManager.systemInstruction,
            history = conversationManager.getHistoryJson(),
            onTokenReceived = { token ->
                fullResponse.append(token)
                
                // Detect Emotion Tag
                if (fullResponse.startsWith("[") && fullResponse.contains("]")) {
                    val tagEnd = fullResponse.indexOf("]")
                    val tag = fullResponse.substring(1, tagEnd).uppercase()
                    try {
                        val expression = AvatarExpression.valueOf(tag)
                        runOnUiThread { avatarController.setExpression(expression) }
                    } catch (e: Exception) {}
                    
                    val actualContent = fullResponse.substring(tagEnd + 1).trim()
                    if (actualContent.isNotEmpty()) {
                        sentenceBuffer.append(token)
                    }
                } else {
                    sentenceBuffer.append(token)
                }
                
                if (token.contains(".") || token.contains("!") || token.contains("?")) {
                    val sentence = sentenceBuffer.toString().trim()
                    if (sentence.isNotEmpty()) {
                        runOnUiThread {
                            if (avatarController.currentState != AvatarState.SPEAKING) {
                                avatarController.updateState(AvatarState.SPEAKING)
                            }
                            binding.statusText.text = "Speaking..."
                            ttsManager.speak(sentence)
                        }
                        sentenceBuffer = StringBuilder()
                    }
                }
            },
            onComplete = {
                val remaining = sentenceBuffer.toString().trim()
                if (remaining.isNotEmpty()) {
                    runOnUiThread { ttsManager.speak(remaining) }
                }
                conversationManager.addModelMessage(fullResponse.toString())
            },
            onError = { error ->
                runOnUiThread {
                    Toast.makeText(this, "AI Error: ${error.message}", Toast.LENGTH_SHORT).show()
                    avatarController.updateState(AvatarState.IDLE)
                    avatarController.setExpression(AvatarExpression.CONFUSED)
                    binding.statusText.text = "Error"
                }
            }
        )
    }

    private fun updateUIState() {
        runOnUiThread {
            if (isListening) {
                binding.micButton.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            } else {
                binding.micButton.setImageResource(android.R.drawable.ic_btn_speak_now)
            }
        }
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechInputManager.destroy()
        ttsManager.destroy()
        audioAnalyzer.stop()
    }
}
