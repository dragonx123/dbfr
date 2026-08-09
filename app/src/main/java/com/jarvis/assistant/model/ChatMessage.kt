package com.jarvis.assistant.model

/** Who authored a turn in the conversation shown in the chat UI. */
enum class Sender { USER, JARVIS, SYSTEM }

/** One row in a [DataCard] — a web result, a fetched page, etc. */
data class DataCardEntry(
    val title: String,
    val snippet: String,
    val url: String,
)

/**
 * Structured data Jarvis "pulls up" mid-conversation (web search results,
 * fetched pages). Rendered as an MCU-style bordered data panel in the chat
 * instead of plain prose — see `DataCardPanel` in `ui/ChatScreen.kt`.
 */
data class DataCard(
    val title: String,
    val entries: List<DataCardEntry>,
)

/**
 * A single chat bubble. [isStreaming] is true while the model is still
 * generating [text] token-by-token so the UI can show a typing indicator.
 * A message with a [dataCard] renders as a data panel instead of a bubble.
 */
data class ChatMessage(
    val id: String,
    val sender: Sender,
    val text: String,
    val isStreaming: Boolean = false,
    val timestampMillis: Long = System.currentTimeMillis(),
    val dataCard: DataCard? = null,
)
