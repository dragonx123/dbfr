package com.jarvis.assistant.ai

import com.jarvis.assistant.model.CloudProvider
import com.jarvis.assistant.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "CloudApiBackend"

/**
 * [ChatBackend] that talks to a hosted LLM API with the user's own API key:
 * Google Gemini, Anthropic Claude, or any OpenAI-compatible endpoint
 * (OpenAI itself, Groq, Mistral, LM Studio, ... via a custom base URL).
 *
 * All three speak server-sent events for streaming; only the request body
 * shape and the path to the text delta differ, so one class covers them
 * with a small per-provider switch.
 */
class CloudApiChatBackend(
    private val provider: CloudProvider,
    private val apiKey: String,
    private val model: String,
    baseUrl: String,
    private val systemInstruction: String,
) : ChatBackend {

    private val baseUrl = baseUrl.trimEnd('/')

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // streaming: no fixed read timeout
        .build()

    /** role is "user" or "assistant"; the system prompt is sent separately per provider. */
    private val history = mutableListOf<Pair<String, String>>()

    override suspend fun initialize() {
        if (apiKey.isBlank()) throw IOException("No API key set — add one in Settings.")
        if (model.isBlank()) throw IOException("No model set — pick one in Settings.")
        history.clear()
        AppLogger.i(TAG, "Configured $provider (model=$model)")
    }

    override fun sendMessageStream(userText: String): Flow<String> = flow {
        AppLogger.i(TAG, "sendMessage via $provider: \"${userText.take(80)}\"")
        history += "user" to userText

        val request = buildRequest()
        val assistantText = StringBuilder()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val body = resp.body?.string().orEmpty().take(400)
                AppLogger.e(TAG, "$provider HTTP ${resp.code}: $body")
                throw IOException(friendlyHttpError(resp.code, body))
            }
            val source = resp.body?.source() ?: throw IOException("$provider returned an empty body")

            // All three providers stream SSE: lines of "data: {json}" separated
            // by blank lines (Anthropic adds "event:" lines we can ignore —
            // the data payload carries its own "type" field).
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                val delta = runCatching { extractDelta(JSONObject(payload)) }.getOrNull()
                if (!delta.isNullOrEmpty()) {
                    assistantText.append(delta)
                    emit(delta)
                }
            }
        }

        history += "assistant" to assistantText.toString()
        AppLogger.i(TAG, "Generation complete (${assistantText.length} chars)")
    }.flowOn(Dispatchers.IO)

    private fun buildRequest(): Request = when (provider) {
        CloudProvider.OPENAI_COMPAT -> {
            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemInstruction))
                history.forEach { (role, text) ->
                    put(JSONObject().put("role", role).put("content", text))
                }
            }
            val body = JSONObject()
                .put("model", model)
                .put("stream", true)
                .put("messages", messages)
            Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toString().toRequestBody(JSON))
                .build()
        }

        CloudProvider.ANTHROPIC -> {
            val messages = JSONArray().apply {
                history.forEach { (role, text) ->
                    put(JSONObject().put("role", role).put("content", text))
                }
            }
            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", 2048)
                .put("stream", true)
                .put("system", systemInstruction)
                .put("messages", messages)
            Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .post(body.toString().toRequestBody(JSON))
                .build()
        }

        CloudProvider.GEMINI -> {
            val contents = JSONArray().apply {
                history.forEach { (role, text) ->
                    put(
                        JSONObject()
                            .put("role", if (role == "assistant") "model" else "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", text)))
                    )
                }
            }
            val body = JSONObject()
                .put(
                    "system_instruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
                )
                .put("contents", contents)
            Request.Builder()
                .url(
                    "https://generativelanguage.googleapis.com/v1beta/models/" +
                        "$model:streamGenerateContent?alt=sse&key=$apiKey"
                )
                .post(body.toString().toRequestBody(JSON))
                .build()
        }
    }

    /** Pulls the incremental text out of one SSE data payload, per provider. */
    private fun extractDelta(json: JSONObject): String = when (provider) {
        CloudProvider.OPENAI_COMPAT ->
            json.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("delta")?.optString("content").orEmpty()

        CloudProvider.ANTHROPIC ->
            if (json.optString("type") == "content_block_delta") {
                json.optJSONObject("delta")?.optString("text").orEmpty()
            } else ""

        CloudProvider.GEMINI ->
            json.optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
                ?.optJSONObject(0)?.optString("text").orEmpty()
    }

    private fun friendlyHttpError(code: Int, body: String): String = when (code) {
        401, 403 -> "$provider rejected the API key (HTTP $code). Check it in Settings."
        404 -> "$provider doesn't know the model \"$model\" (HTTP 404). Check the model name in Settings."
        429 -> "$provider rate limit or quota hit (HTTP 429). Wait a bit or check your plan."
        else -> "$provider returned HTTP $code: $body"
    }

    override fun close() {
        // Connection pool is fine to leave to GC, same as OllamaChatBackend.
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
