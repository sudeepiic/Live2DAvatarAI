package com.example.live2davatarai.network

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.example.live2davatarai.util.LogUtil

class OpenAIClient(private val apiKey: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Streaming responses can be long-lived
        .readTimeout(0, TimeUnit.SECONDS)
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
        
        LogUtil.d("OpenAIClient", "Requesting OpenAI ($model)...")

        val messages = JSONArray()
        
        // System Message
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", systemInstruction)
        })

        // Conversation History (already OpenAI format)
        for (i in 0 until history.length()) {
            val msg = history.getJSONObject(i)
            val role = msg.optString("role", "user")
            val content = msg.optString("content", "")
            if (content.isNotBlank()) {
                messages.put(JSONObject().apply {
                    put("role", role)
                    put("content", content)
                })
            }
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
                LogUtil.e("OpenAIClient", "Network Failure", e)
                onError(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    val errorMsg = "API Error ${response.code}: $errorBody"
                    LogUtil.e("OpenAIClient", errorMsg)
                    onError(IOException(errorMsg))
                    response.close()
                    return
                }

                val responseBody = response.body
                if (responseBody == null) {
                    onError(IOException("Empty response body"))
                    response.close()
                    return
                }
                val source = responseBody.source()
                try {
                    LogUtil.d("OpenAIClient", "Stream started")
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.startsWith("data: ")) {
                            val data = line.substring(6).trim()
                            if (data == "[DONE]") {
                                LogUtil.d("OpenAIClient", "Stream [DONE] received")
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
                                LogUtil.w("OpenAIClient", "JSON Parse Error: ${e.message}")
                            }
                        }
                    }
                    LogUtil.d("OpenAIClient", "Stream completed naturally")
                    onComplete()
                } catch (e: Exception) {
                    LogUtil.e("OpenAIClient", "Stream Parsing Error", e)
                    onError(e)
                } finally {
                    response.close()
                }
            }
        })
    }
}
