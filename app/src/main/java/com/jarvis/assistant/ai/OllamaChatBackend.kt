package com.jarvis.assistant.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val SYSTEM_INSTRUCTION =
    "You are Jarvis, a concise, helpful voice assistant. Keep replies short " +
        "and natural unless the user asks for detail."

/**
 * [ChatBackend] that talks to a remote/LAN [Ollama](https://ollama.com)
 * server instead of running inference on-device. Trades "fully offline" for
 * "use whatever machine is actually running the model" — handy when the
 * phone itself is too weak/slow for a good model but there's a PC or
 * homelab box on the network running `ollama serve`.
 *
 * Note: unlike [LiteRtChatBackend], this backend does **not** wire up
 * [com.jarvis.assistant.tools.JarvisTools] — Ollama's tool-calling wire
 * format is a separate integration, and most locally-run models don't
 * support it reliably. This is plain chat only.
 */
class OllamaChatBackend(
    private val baseUrl: String,
    private val model: String,
) : ChatBackend {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // streaming response: no fixed read timeout
        .build()

    private val history = mutableListOf<JSONObject>()

    override suspend fun initialize() {
        history.clear()
        history += message("system", SYSTEM_INSTRUCTION)
    }

    override fun sendMessageStream(userText: String): Flow<String> = callbackFlow {
        history += message("user", userText)

        val requestJson = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("messages", JSONArray(history))
        }
        val body = requestJson.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/chat")
            .post(body)
            .build()

        val assistantText = StringBuilder()
        val call = client.newCall(request)

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close(IOException("Couldn't reach Ollama at $baseUrl: ${e.message}", e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        close(IOException("Ollama returned HTTP ${resp.code}: ${resp.body?.string().orEmpty()}"))
                        return
                    }
                    val source = resp.body?.source()
                    if (source == null) {
                        close(IOException("Ollama returned an empty response body"))
                        return
                    }
                    try {
                        // Ollama streams newline-delimited JSON objects, one per token/chunk.
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isBlank()) continue
                            val json = JSONObject(line)
                            json.optString("error", "").let {
                                if (it.isNotEmpty()) throw IOException(it)
                            }
                            val chunk = json.optJSONObject("message")?.optString("content").orEmpty()
                            if (chunk.isNotEmpty()) {
                                assistantText.append(chunk)
                                trySend(chunk)
                            }
                            if (json.optBoolean("done", false)) break
                        }
                        history += message("assistant", assistantText.toString())
                        close()
                    } catch (e: Exception) {
                        close(e)
                    }
                }
            }
        })

        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    override fun close() {
        // OkHttpClient has no per-call resources to release beyond the connection
        // pool, which is fine to let the JVM/GC reclaim when this backend is dropped.
    }

    private fun message(role: String, content: String) =
        JSONObject().put("role", role).put("content", content)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * Fetches the list of model names the Ollama server at [baseUrl] currently
         * has pulled (`GET /api/tags`), for populating a picker in Settings.
         */
        suspend fun listModels(baseUrl: String): Result<List<String>> = withContext(Dispatchers.IO) {
            runCatching {
                val client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder().url(baseUrl.trimEnd('/') + "/api/tags").build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body?.string().orEmpty()
                    val models = JSONObject(body).optJSONArray("models") ?: JSONArray()
                    (0 until models.length()).map { i -> models.getJSONObject(i).getString("name") }
                }
            }
        }
    }
}
