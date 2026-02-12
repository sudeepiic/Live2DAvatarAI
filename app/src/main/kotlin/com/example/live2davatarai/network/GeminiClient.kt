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
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    fun streamResponse(
        systemInstruction: String,
        history: JSONArray,
        onTokenReceived: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        if (apiKey.isEmpty()) {
            onError(Exception("API Key is missing"))
            return
        }

        // v1beta is recommended for gemini-1.5-flash
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:streamGenerateContent?alt=sse&key=$apiKey"
        
        Log.d("GeminiClient", "Requesting Gemini API...")

        val bodyJson = JSONObject().apply {
            put("contents", history)
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
            })
        }

        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("GeminiClient", "Network Failure", e)
                onError(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    val errorMsg = "API Error ${response.code}: $errorBody"
                    Log.e("GeminiClient", errorMsg)
                    onError(IOException(errorMsg))
                    response.close()
                    return
                }

                val source = response.body?.source() ?: return
                try {
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.startsWith("data: ")) {
                            val data = line.substring(6).trim()
                            if (data.isEmpty()) continue
                            
                            val json = JSONObject(data)
                            val candidates = json.optJSONArray("candidates")
                            if (candidates != null && candidates.length() > 0) {
                                val content = candidates.getJSONObject(0).optJSONObject("content")
                                val parts = content?.optJSONArray("parts")
                                if (parts != null && parts.length() > 0) {
                                    val text = parts.getJSONObject(0).optString("text")
                                    if (text.isNotEmpty()) {
                                        onTokenReceived(text)
                                    }
                                }
                            }
                        }
                    }
                    onComplete()
                } catch (e: Exception) {
                    Log.e("GeminiClient", "Stream Parsing Error", e)
                    onError(e)
                } finally {
                    response.close()
                }
            }
        })
    }
}
