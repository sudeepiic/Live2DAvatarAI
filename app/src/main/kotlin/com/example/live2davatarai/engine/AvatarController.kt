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
    @Volatile var bodyAngleX: Float = 0f
    @Volatile var eyeBlink: Float = 1f
    @Volatile var eyeOpen: Float = 1f // 0 to 1
    @Volatile var browY: Float = 0f   // -1 to 1

    private var lastUpdate = System.currentTimeMillis()

    fun updateState(state: AvatarState) {
        currentState = state
    }

    fun setExpression(expression: AvatarExpression) {
        currentExpression = expression
    }

    fun setSpeechAmplitude(amplitude: Float) {
        if (currentState == AvatarState.SPEAKING) {
            mouthOpenY = amplitude
        }
    }

    fun updateAnimations() {
        val now = System.currentTimeMillis()
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
