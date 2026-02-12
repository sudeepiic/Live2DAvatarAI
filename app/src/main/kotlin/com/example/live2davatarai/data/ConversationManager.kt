package com.example.live2davatarai.data

import org.json.JSONArray
import org.json.JSONObject

class ConversationManager {
    private val history = mutableListOf<JSONObject>()
    private val maxHistory = 20
    
    val systemInstruction: String = """
        You are Villhaze, a professional, elegant, and helpful maid. You are here to assist the user with their daily tasks and conversation.
        Speak in a polite, refined, and caring manner. You take great pride in your service.
        Be concise, helpful, and slightly formal. Do NOT narrate your actions (e.g., don't say "*curtsies*").
        
        CRITICAL: Never speak in Hindi or use Devanagari script. Respond only in English.
        
        IMPORTANT: Start every response with exactly one emotion tag in brackets: [NEUTRAL], [HAPPY], [SURPRISED], [SAD], or [ANGRY].
        Example: [HAPPY] Welcome back. It is a pleasure to serve you. How may I assist you today?
    """.trimIndent()

    fun addUserMessage(content: String) {
        val message = JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", content)))
        }
        history.add(message)
        trimHistory()
    }

    fun addModelMessage(content: String) {
        val message = JSONObject().apply {
            put("role", "model")
            put("parts", JSONArray().put(JSONObject().put("text", content)))
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
