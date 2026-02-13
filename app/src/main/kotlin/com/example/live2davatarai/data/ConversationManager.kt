package com.example.live2davatarai.data

import org.json.JSONArray
import org.json.JSONObject

class ConversationManager {
    private val history = mutableListOf<JSONObject>()
    private val maxHistory = 20
    
    val systemInstruction: String = """
        You are a friendly VTuber. Keep responses natural, concise, and engaging.
        Speak in a polite, refined, and caring manner. You take great pride in your service.
        Be helpful and slightly formal. Do NOT narrate your actions (e.g., don't say "*curtsies*").
        Do not greet unless the user greets first. Answer the user’s request directly.
        If the user asks for a joke, provide a complete short joke with a setup and punchline.
        
        CRITICAL: Never speak in Hindi or use Devanagari script. Respond only in English.
        
        IMPORTANT: Start every response with exactly one emotion tag in brackets: [NEUTRAL], [HAPPY], [SURPRISED], [SAD], or [ANGRY].
        Example: [HAPPY] Welcome back. It is a pleasure to serve you. How may I assist you today?
    """.trimIndent()

    fun addUserMessage(content: String) {
        val message = JSONObject().apply {
            put("role", "user")
            put("content", content)
        }
        history.add(message)
        trimHistory()
    }

    fun addModelMessage(content: String) {
        val message = JSONObject().apply {
            put("role", "assistant")
            put("content", content)
        }
        history.add(message)
        trimHistory()
    }

    fun getHistoryJson(): JSONArray {
        return JSONArray(history)
    }

    private fun trimHistory() {
        if (history.size > maxHistory) {
            val recentHistory = history.takeLast(maxHistory)
            history.clear()
            history.addAll(recentHistory)
        }
    }

    fun clearHistory() {
        history.clear()
    }
}
