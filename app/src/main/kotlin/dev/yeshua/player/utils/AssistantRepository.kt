/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Free, no-key cloud chat backend for Yeshua's built-in assistant.
 *
 * Uses the Pollinations.ai OpenAI-compatible text endpoint. No API key, no
 * account, no sign-up. Online-only by nature: callers must check connectivity
 * first and degrade gracefully — this client never retries on its own.
 */
object AssistantRepository {
    private const val ENDPOINT = "https://text.pollinations.ai/openai"
    private const val MODEL = "openai"

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

    data class ChatMessage(
        val role: String, // "system" | "user" | "assistant"
        val content: String,
    )

    /**
     * Sends the conversation and returns the assistant's reply text.
     * Returns [Result.failure] on any network/API/parse error — callers show a
     * graceful offline message instead of retrying.
     */
    suspend fun chat(messages: List<ChatMessage>): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val jsonMessages = JSONArray()
                for (m in messages) {
                    jsonMessages.put(
                        JSONObject()
                            .put("role", m.role)
                            .put("content", m.content),
                    )
                }
                val body =
                    JSONObject()
                        .put("model", MODEL)
                        .put("messages", jsonMessages)
                        .put("max_tokens", 600)
                        .put("temperature", 0.7)
                        .toString()
                        .toRequestBody("application/json".toMediaType())

                val request =
                    Request
                        .Builder()
                        .url(ENDPOINT)
                        .post(body)
                        .header("Content-Type", "application/json")
                        .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Assistant service returned ${response.code}")
                    }
                    val raw = response.body?.string().orEmpty()
                    val content =
                        JSONObject(raw)
                            .getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                    content.trim().ifEmpty {
                        throw IllegalStateException("Empty assistant reply")
                    }
                }
            }
        }
}
