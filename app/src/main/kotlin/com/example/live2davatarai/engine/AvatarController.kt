package com.example.live2davatarai.engine

enum class AvatarState {
    IDLE, LISTENING, THINKING, SPEAKING
}

enum class AvatarExpression {
    NEUTRAL, HAPPY, SURPRISED, SLEEPY, SAD, ANGRY, CONFUSED
}

class AvatarController {
    @Volatile
    var currentState: AvatarState = AvatarState.IDLE
        private set
    
    @Volatile
    var currentExpression: AvatarExpression = AvatarExpression.NEUTRAL
        private set

    // Live2D Parameters
    @Volatile var mouthOpenY: Float = 0f
    @Volatile var mouthForm: Float = 0f // -1 to 1 (Sad to Happy)
    @Volatile var bodyAngleX: Float = 0f
    @Volatile var eyeBlink: Float = 1f
    @Volatile var eyeOpen: Float = 1f // 0 to 1
    @Volatile var browY: Float = 0f   // -1 to 1

    private var targetMouthY: Float = 0f
    private val smoothingFactor = 0.2f // Lower = smoother

    private var lastUpdate = System.currentTimeMillis()

    fun updateState(state: AvatarState) {
        currentState = state
    }

    fun setExpression(expression: AvatarExpression) {
        currentExpression = expression
    }

    fun setSpeechAmplitude(amplitude: Float) {
        if (currentState == AvatarState.SPEAKING) {
            // Boost amplitude sensitivity for Wanko model
            targetMouthY = (amplitude * 1.5f).coerceIn(0f, 1.0f)
        } else {
            targetMouthY = 0f
        }
    }

    fun updateAnimations() {
        val now = System.currentTimeMillis()
        
        // Handle Lip-Sync
        if (currentState == AvatarState.SPEAKING) {
            // Procedural Lip-Sync: Move mouth in a natural way while speaking
            val sineVal = kotlin.math.sin(now / 80.0).toFloat()
            targetMouthY = (sineVal * 0.8f).coerceIn(0f, 1.0f)
            
            // Viseme Mapping: Vary mouth shape (smiling vs wide) to mimic phonemes
            mouthForm = kotlin.math.cos(now / 120.0).toFloat() // Oscillate shape
            
            if (now % 500 < 20) {
                android.util.Log.d("AvatarController", "Mouth Target: $targetMouthY, Form: $mouthForm")
            }
        } else {
            targetMouthY = 0f
            mouthForm = 0f
        }

        // Linear interpolation for smooth mouth
        mouthOpenY = mouthOpenY + (targetMouthY - mouthOpenY) * smoothingFactor
        val deltaTime = (now - lastUpdate) / 1000f
        lastUpdate = now

        // 1. Handle Expressions (Facial logic)
        when (currentExpression) {
            AvatarExpression.NEUTRAL -> { eyeOpen = 1f; browY = 0f }
            AvatarExpression.HAPPY -> { eyeOpen = 0.8f; browY = 0.5f }
            AvatarExpression.SURPRISED -> { eyeOpen = 1.2f; browY = 0.8f }
            AvatarExpression.SLEEPY -> { eyeOpen = 0.3f; browY = -0.2f }
            AvatarExpression.SAD -> { eyeOpen = 0.6f; browY = -0.5f }
            AvatarExpression.ANGRY -> { eyeOpen = 0.9f; browY = -0.8f }
            AvatarExpression.CONFUSED -> { eyeOpen = 0.7f; browY = 0.3f }
        }

        // 2. Handle States (Movement logic)
        when (currentState) {
            AvatarState.IDLE -> {
                bodyAngleX = kotlin.math.sin(now / 1500.0).toFloat() * 2f
                mouthOpenY = 0f
            }
            AvatarState.LISTENING -> {
                bodyAngleX = 5f
            }
            AvatarState.THINKING -> {
                bodyAngleX = -5f
            }
            AvatarState.SPEAKING -> {
                // mouthOpenY is set by AudioAnalyzer
            }
        }
    }
}
