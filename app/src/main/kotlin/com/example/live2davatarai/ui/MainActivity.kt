package com.example.live2davatarai.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.live2davatarai.audio.SpeechInputManager
import com.example.live2davatarai.audio.TTSManager
import com.example.live2davatarai.data.ConversationManager
import com.example.live2davatarai.databinding.ActivityMainBinding
import com.example.live2davatarai.engine.AvatarController
import com.example.live2davatarai.engine.AvatarState
import com.example.live2davatarai.network.GeminiClient

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var speechInputManager: SpeechInputManager
    private lateinit var ttsManager: TTSManager
    private lateinit var geminiClient: GeminiClient
    private lateinit var conversationManager: ConversationManager
    private lateinit var avatarController: AvatarController

    private val PERMISSION_REQUEST_CODE = 100
    private var isListening = false

    // Provided Gemini API Key
    private val API_KEY = "AIzaSyDxjbQ8mNukozMskIwLU5euPeW3xnR4TRM"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initModules()
        setupListeners()
        checkPermissions()
    }

    private fun initModules() {
        avatarController = AvatarController()
        binding.avatarSurfaceView.setController(avatarController)
        conversationManager = ConversationManager()
        geminiClient = GeminiClient(API_KEY)
        
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
            if (isListening) {
                speechInputManager.stopListening()
            } else {
                ttsManager.stop()
                speechInputManager.startListening()
                avatarController.updateState(AvatarState.LISTENING)
                binding.statusText.text = "Listening..."
            }
        }
    }

    private fun handleUserInput(input: String) {
        if (input.isBlank()) return
        
        binding.statusText.text = "Thinking..."
        avatarController.updateState(AvatarState.THINKING)
        conversationManager.addUserMessage(input)

        val fullResponse = StringBuilder()
        var sentenceBuffer = StringBuilder()

        geminiClient.streamResponse(
            systemInstruction = conversationManager.systemInstruction,
            history = conversationManager.getHistoryJson(),
            onTokenReceived = { token ->
                fullResponse.append(token)
                
                // Detect Emotion Tag (e.g., [HAPPY])
                if (fullResponse.startsWith("[") && fullResponse.contains("]")) {
                    val tagEnd = fullResponse.indexOf("]")
                    val tag = fullResponse.substring(1, tagEnd).uppercase()
                    try {
                        val expression = com.example.live2davatarai.engine.AvatarExpression.valueOf(tag)
                        runOnUiThread { avatarController.setExpression(expression) }
                    } catch (e: Exception) {}
                    
                    // Filter the tag out of the spoken text
                    val actualContent = fullResponse.substring(tagEnd + 1).trim()
                    if (actualContent.isNotEmpty()) {
                        sentenceBuffer.append(token)
                    }
                } else {
                    sentenceBuffer.append(token)
                }
                
                // If we have a complete sentence, speak it
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
                    binding.statusText.text = "Idle"
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechInputManager.destroy()
        ttsManager.destroy()
    }
}
