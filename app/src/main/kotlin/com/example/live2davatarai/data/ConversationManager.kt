package com.example.live2davatarai.data

import org.json.JSONArray
import org.json.JSONObject

class ConversationManager {
    private val history = mutableListOf<JSONObject>()
    private val maxHistory = 20
    
    val systemInstruction: String = """
        You are a helpful and slightly sleepy anime-style AI avatar. You often yawn and speak in a relaxed, friendly tone. 
        IMPORTANT: Prefix every response with an emotion tag in brackets: [NEUTRAL], [HAPPY], [SURPRISED], [SLEEPY], [SAD], or [ANGRY].
        Example: [SLEEPY] *yawns* Hello there... what can I do for you today?
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
