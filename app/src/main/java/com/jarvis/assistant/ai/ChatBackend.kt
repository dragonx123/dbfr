package com.jarvis.assistant.ai

import kotlinx.coroutines.flow.Flow

/**
 * A source of chat replies. [com.jarvis.assistant.ChatViewModel] talks to
 * whichever implementation is configured (on-device LiteRT-LM, or a remote
 * Ollama server) through this one interface, so the rest of the app doesn't
 * care which is active.
 */
interface ChatBackend {

    /** Prepares the backend to receive messages. May be slow (loading a model, network round-trip). */
    suspend fun initialize()

    /** Sends [userText] and streams back incremental response chunks. */
    fun sendMessageStream(userText: String): Flow<String>

    /**
     * True if this backend can accept an image alongside the text — i.e.
     * whether "look at my screen" is possible at all. The on-device LiteRT
     * models this app loads are text-only, so they answer false and the UI
     * falls back to the accessibility service's screen-text reading.
     */
    val supportsImages: Boolean get() = false

    /**
     * Sends [userText] together with a base64-encoded JPEG. Only meaningful
     * when [supportsImages]; the default just drops the image so callers
     * don't have to branch.
     */
    fun sendMessageWithImageStream(userText: String, base64Jpeg: String): Flow<String> =
        sendMessageStream(userText)

    /** Releases any resources (native engine, HTTP client, ...). */
    fun close()
}
