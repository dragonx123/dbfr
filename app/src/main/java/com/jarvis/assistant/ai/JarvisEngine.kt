package com.jarvis.assistant.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import com.jarvis.assistant.tools.JarvisTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private const val SYSTEM_INSTRUCTION = """
You are Jarvis, a concise, helpful voice assistant running entirely on the
user's Android phone. Keep spoken replies short and natural — a sentence or
two unless the user asks for detail. When the user asks you to do something
on their phone (open an app, set an alarm or timer, search the web, text or
call someone, turn on the flashlight, add a calendar event, or navigate
somewhere), call the matching tool instead of just describing what to do.
"""

/**
 * Thin coroutine-friendly wrapper around the LiteRT-LM [Engine] / [Conversation]
 * pair that runs the on-device model and exposes streaming text replies.
 *
 * https://github.com/google-ai-edge/LiteRT-LM
 */
class JarvisEngine(private val appContext: Context) {

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    val isReady: Boolean
        get() = conversation != null

    /**
     * Loads [modelPath] into memory. This can take several seconds, so callers
     * must invoke it off the main thread (this function already hops to
     * [Dispatchers.IO] internally).
     */
    suspend fun initialize(modelPath: String, cacheDir: String, useGpu: Boolean = false) {
        withContext(Dispatchers.IO) {
            close()

            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = if (useGpu) Backend.GPU() else Backend.CPU(),
                cacheDir = cacheDir,
            )
            Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
            val newEngine = Engine(engineConfig)
            newEngine.initialize()

            val conversationConfig = ConversationConfig(
                systemInstruction = Contents.of(SYSTEM_INSTRUCTION.trim()),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7),
                tools = listOf(tool(JarvisTools(appContext))),
            )

            engine = newEngine
            conversation = newEngine.createConversation(conversationConfig)
        }
    }

    /**
     * Sends [userText] and streams back incremental response chunks. Tool
     * calls (device control) are executed automatically by the engine before
     * the final answer is streamed back.
     */
    fun sendMessageStream(userText: String): Flow<String> {
        val activeConversation = conversation ?: error("JarvisEngine.initialize() must complete first")
        return activeConversation.sendMessageAsync(userText).map { it.toString() }
    }

    fun close() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
    }
}
