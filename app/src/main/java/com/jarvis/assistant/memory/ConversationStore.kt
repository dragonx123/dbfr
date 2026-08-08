package com.jarvis.assistant.memory

import android.content.Context
import com.jarvis.assistant.model.ChatMessage
import com.jarvis.assistant.model.Sender
import com.jarvis.assistant.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "Conversation"

/**
 * Keeps the chat transcript across app restarts, so closing Jarvis doesn't
 * wipe the conversation the way it used to.
 *
 * Only the visible transcript is persisted — data-card payloads are
 * deliberately dropped, since a saved search result panel would be stale
 * (and often wrong) by the time it's reloaded days later. The text of the
 * reply that used those results is kept, which is the part worth having.
 */
class ConversationStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    // Single-threaded, off the UI thread: saving ran on every completed
    // reply and the transcript can reach 200 messages, so this was real
    // disk I/O on the main thread once per turn.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** Reads the saved transcript. Call from a background context. */
    suspend fun loadAsync(): List<ChatMessage> = withContext(Dispatchers.IO) { load() }

    private fun load(): List<ChatMessage> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { i ->
            runCatching {
                val obj = array.getJSONObject(i)
                ChatMessage(
                    id = obj.getString("id"),
                    sender = Sender.valueOf(obj.getString("sender")),
                    text = obj.getString("text"),
                    isStreaming = false,
                    timestampMillis = obj.optLong("timestamp", System.currentTimeMillis()),
                )
            }.getOrNull()
        }
    }.getOrElse {
        AppLogger.e(TAG, "Couldn't read saved conversation", it)
        emptyList()
    }

    fun save(messages: List<ChatMessage>) {
        val recent = messages
            .filter { it.dataCard == null && !it.isStreaming && it.text.isNotBlank() }
            .takeLast(MAX_MESSAGES)
        io.launch {
            runCatching {
                val array = JSONArray()
                recent.forEach { message ->
                    array.put(
                        JSONObject()
                            .put("id", message.id)
                            .put("sender", message.sender.name)
                            .put("text", message.text)
                            .put("timestamp", message.timestampMillis)
                    )
                }
                file.writeText(array.toString())
            }.onFailure { AppLogger.e(TAG, "Couldn't save conversation", it) }
        }
    }

    fun clear() {
        io.launch {
            runCatching { file.delete() }
            AppLogger.i(TAG, "Conversation history cleared")
        }
    }

    /**
     * A compact recap of the last exchanges, for re-seeding a freshly
     * connected backend. Backends start with no history of their own — a
     * new `Conversation`, a cleared message list — so without this, Jarvis
     * loses the thread on every app restart and backend switch even though
     * the user can still see the transcript on screen.
     */
    fun recapBlock(messages: List<ChatMessage>): String {
        val relevant = messages
            .filter { it.sender != Sender.SYSTEM && it.dataCard == null && it.text.isNotBlank() }
            .takeLast(RECAP_MESSAGES)
        if (relevant.isEmpty()) return ""
        return buildString {
            appendLine("[Earlier in your conversation with this user:]")
            relevant.forEach { message ->
                val who = if (message.sender == Sender.USER) "User" else "You"
                appendLine("$who: ${message.text.take(300)}")
            }
            // Explicit terminator: without it the transcript ran straight into
            // whatever followed, and the model treated the next line as another
            // turn of this block rather than the live question.
            appendLine("[End of earlier conversation.]")
            appendLine()
        }
    }

    companion object {
        private const val FILE_NAME = "jarvis_conversation.json"
        private const val MAX_MESSAGES = 200
        private const val RECAP_MESSAGES = 10
    }
}
