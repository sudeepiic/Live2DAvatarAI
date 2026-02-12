package com.example.live2davatarai.network

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class GeminiClient(private val apiKey: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun streamResponse(
        systemInstruction: String,
        history: JSONArray,
        onTokenReceived: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        // OpenAI Endpoint and Model
        val url = "https://api.openai.com/v1/chat/completions"
        val model = "gpt-4.1-nano" 
        
        Log.d("AiClient", "Requesting OpenAI ($model)...")

        val messages = JSONArray()
        
        // System Message
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", systemInstruction)
        })

        // Conversation History
        for (i in 0 until history.length()) {
            val msg = history.getJSONObject(i)
            val role = msg.getString("role")
            val text = msg.getJSONArray("parts").getJSONObject(0).getString("text")
            
            messages.put(JSONObject().apply {
                put("role", if (role == "model") "assistant" else "user")
                put("content", text)
            })
        }

        val bodyJson = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("stream", true)
            put("max_tokens", 4096)
            put("temperature", 0.7)
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("AiClient", "Network Failure", e)
                onError(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    val errorMsg = "API Error ${response.code}: $errorBody"
                    Log.e("AiClient", errorMsg)
                    onError(IOException(errorMsg))
                    response.close()
                    return
                }

                val source = response.body?.source() ?: return
                try {
                    Log.d("AiClient", "Stream started")
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        Log.v("AiClient", "Line: $line")
                        if (line.startsWith("data: ")) {
                            val data = line.substring(6).trim()
                            if (data == "[DONE]") {
                                Log.d("AiClient", "Stream [DONE] received")
                                break
                            }
                            if (data.isEmpty()) continue
                            
                            try {
                                val json = JSONObject(data)
                                val choices = json.optJSONArray("choices")
                                if (choices != null && choices.length() > 0) {
                                    val delta = choices.getJSONObject(0).optJSONObject("delta")
                                    val content = delta?.optString("content") ?: ""
                                    if (content.isNotEmpty()) {
                                        onTokenReceived(content)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w("AiClient", "JSON Parse Error: ${e.message}")
                            }
                        }
                    }
                    Log.d("AiClient", "Stream completed naturally")
                    onComplete()
                } catch (e: Exception) {
                    Log.e("AiClient", "Stream Parsing Error", e)
                    onError(e)
                } finally {
                    response.close()
                }
            }
        })
    }
}
