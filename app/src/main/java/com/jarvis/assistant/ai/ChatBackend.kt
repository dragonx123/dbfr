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

    /** Releases any resources (native engine, HTTP client, ...). */
    fun close()
}
