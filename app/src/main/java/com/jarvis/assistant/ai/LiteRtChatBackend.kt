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
 * [ChatBackend] that runs an LLM entirely on-device via LiteRT-LM, with
 * device-control tool calling ([JarvisTools]) enabled.
 *
 * https://github.com/google-ai-edge/LiteRT-LM
 */
class LiteRtChatBackend(
    private val appContext: Context,
    private val modelPath: String,
    private val cacheDir: String,
    private val useGpu: Boolean = false,
) : ChatBackend {

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    /**
     * Loads the model into memory. This can take several seconds, so callers
     * must invoke it off the main thread (this function already hops to
     * [Dispatchers.IO] internally).
     */
    override suspend fun initialize() {
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
    override fun sendMessageStream(userText: String): Flow<String> {
        val activeConversation = conversation ?: error("LiteRtChatBackend.initialize() must complete first")
        return activeConversation.sendMessageAsync(userText).map { it.toString() }
    }

    override fun close() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
    }
}
