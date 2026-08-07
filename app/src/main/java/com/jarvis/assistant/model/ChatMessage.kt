package com.jarvis.assistant.model

/** Who authored a turn in the conversation shown in the chat UI. */
enum class Sender { USER, JARVIS, SYSTEM }

/**
 * A single chat bubble. [isStreaming] is true while the model is still
 * generating [text] token-by-token so the UI can show a typing indicator.
 */
data class ChatMessage(
    val id: String,
    val sender: Sender,
    val text: String,
    val isStreaming: Boolean = false,
    val timestampMillis: Long = System.currentTimeMillis(),
)
