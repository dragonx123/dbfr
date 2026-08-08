package com.jarvis.assistant.memory

import android.content.Context
import com.jarvis.assistant.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

private const val TAG = "Memory"

/** What kind of thing a memory is — drives how it's phrased back into the prompt. */
enum class MemoryKind {
    /** A durable fact about the user or their world ("has a dog named Rosie"). */
    FACT,

    /** How the user wants Jarvis to behave ("prefers short answers"). */
    PREFERENCE,

    /** Something that happened, with a time ("flew to Denver on the 3rd"). */
    EVENT,
}

data class Memory(
    val id: String,
    val text: String,
    val kind: MemoryKind,
    val createdAt: Long,
    /** Bumped whenever this memory is retrieved, so useful ones survive pruning. */
    val useCount: Int = 0,
    val lastUsedAt: Long = createdAt,
)

/**
 * Jarvis's long-term memory: things worth carrying between conversations,
 * and the retrieval that pulls the relevant ones back out.
 *
 * Storage is a single JSON file in app-private storage rather than a
 * database — this tops out at a few hundred short strings, and keeping it
 * to one dependency-free file means no Room/schema migration burden on an
 * app that's still changing shape every build.
 *
 * Retrieval is keyword overlap scoring, not embeddings. That's a deliberate
 * tradeoff: on-device embedding models would add hundreds of MB and a
 * second inference pass per turn, and at this corpus size (dozens to
 * hundreds of one-line memories) term overlap plus recency finds the right
 * ones nearly as well.
 */
class MemoryStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    // Single-threaded so writes can never interleave and corrupt the file,
    // and so none of them ever land on the UI thread — recall() mutates
    // use-counts, so a naive implementation wrote to disk on every message.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val _memories = MutableStateFlow<List<Memory>>(emptyList())
    val memories: StateFlow<List<Memory>> = _memories.asStateFlow()

    init {
        io.launch {
            val loaded = load()
            synchronized(this@MemoryStore) {
                // Merge rather than replace: a memory could have been saved
                // in the moment between construction and the load finishing.
                val existing = _memories.value
                val known = existing.map { it.id }.toSet()
                _memories.value = loaded.filterNot { it.id in known } + existing
            }
            AppLogger.i(TAG, "Loaded ${loaded.size} memories")
        }
    }

    /**
     * Saves [text] unless it's already known. Returns the stored memory, or
     * null when it duplicates something already remembered.
     */
    @Synchronized
    fun remember(text: String, kind: MemoryKind = MemoryKind.FACT): Memory? {
        val clean = text.trim().trim('"')
        if (clean.length < 3) return null
        if (_memories.value.any { isNearDuplicate(it.text, clean) }) {
            AppLogger.i(TAG, "Skipped duplicate memory: \"$clean\"")
            return null
        }
        val memory = Memory(UUID.randomUUID().toString(), clean, kind, System.currentTimeMillis())
        _memories.value = (_memories.value + memory).let { all ->
            // Prune least-useful first when over capacity: rarely-retrieved and
            // old go before frequently-recalled ones, regardless of age.
            if (all.size <= MAX_MEMORIES) all
            else all.sortedByDescending { it.useCount * 10_000_000_000L + it.lastUsedAt }
                .take(MAX_MEMORIES)
        }
        persist()
        AppLogger.i(TAG, "Remembered ($kind): \"$clean\"")
        return memory
    }

    @Synchronized
    fun forget(id: String) {
        _memories.value = _memories.value.filterNot { it.id == id }
        persist()
    }

    @Synchronized
    fun forgetAll() {
        _memories.value = emptyList()
        persist()
        AppLogger.i(TAG, "Cleared all memories")
    }

    /**
     * The memories most relevant to [query], best first. Scores term overlap
     * against each memory, with a mild bonus for recent and frequently-used
     * ones so ties break toward what's actually been useful.
     */
    @Synchronized
    fun recall(query: String, limit: Int = 6): List<Memory> {
        val terms = tokenize(query)
        if (terms.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()

        val scored = _memories.value.mapNotNull { memory ->
            val memoryTerms = tokenize(memory.text)
            if (memoryTerms.isEmpty()) return@mapNotNull null
            val overlap = terms.count { term ->
                memoryTerms.any { it == term || (term.length > 4 && it.startsWith(term.take(4))) }
            }
            if (overlap == 0) return@mapNotNull null
            // Normalize by memory length so a long memory doesn't win on
            // sheer word count, then nudge by recency and past usefulness.
            val base = overlap.toFloat() / memoryTerms.size.coerceAtLeast(1)
            val ageDays = (now - memory.lastUsedAt) / 86_400_000f
            val recency = 1f / (1f + ageDays / 30f)
            memory to (base + 0.15f * recency + 0.05f * memory.useCount.coerceAtMost(5))
        }.sortedByDescending { it.second }

        val hits = scored.take(limit).map { it.first }
        if (hits.isNotEmpty()) markUsed(hits.map { it.id }, now)
        return hits
    }

    /** Everything, newest first — for the memory screen. */
    fun all(): List<Memory> = _memories.value.sortedByDescending { it.createdAt }

    /**
     * The block injected ahead of a user message when memories match it.
     * Empty when nothing is relevant, so ordinary turns aren't padded.
     */
    fun recallBlock(query: String): String {
        val hits = recall(query)
        if (hits.isEmpty()) return ""
        return buildString {
            appendLine("[Things you remember about this user — use them if relevant, don't recite them back:]")
            hits.forEach { appendLine("- ${it.text}") }
            appendLine()
        }
    }

    private fun markUsed(ids: List<String>, now: Long) {
        val idSet = ids.toSet()
        _memories.value = _memories.value.map {
            if (it.id in idSet) it.copy(useCount = it.useCount + 1, lastUsedAt = now) else it
        }
        persist()
    }

    /**
     * Catches restatements of the same thing, not just exact repeats — the
     * model tends to save "likes espresso" and later "prefers espresso
     * coffee", which should count as one memory.
     */
    private fun isNearDuplicate(existing: String, candidate: String): Boolean {
        if (existing.equals(candidate, ignoreCase = true)) return true
        val a = tokenize(existing).toSet()
        val b = tokenize(candidate).toSet()
        if (a.isEmpty() || b.isEmpty()) return false
        val shared = a.intersect(b).size.toFloat()
        return shared / minOf(a.size, b.size) >= 0.8f
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 && it !in STOP_WORDS }

    private fun load(): List<Memory> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { i ->
            runCatching {
                val obj = array.getJSONObject(i)
                Memory(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    text = obj.getString("text"),
                    kind = runCatching { MemoryKind.valueOf(obj.optString("kind")) }
                        .getOrDefault(MemoryKind.FACT),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                    useCount = obj.optInt("useCount", 0),
                    lastUsedAt = obj.optLong("lastUsedAt", obj.optLong("createdAt", 0L)),
                )
            }.getOrNull()
        }
    }.getOrElse {
        AppLogger.e(TAG, "Couldn't read memories, starting empty", it)
        emptyList()
    }

    /** Snapshots the current list and writes it off the caller's thread. */
    private fun persist() {
        val snapshot = _memories.value
        io.launch {
            runCatching {
                val array = JSONArray()
                snapshot.forEach { memory ->
                    array.put(
                        JSONObject()
                            .put("id", memory.id)
                            .put("text", memory.text)
                            .put("kind", memory.kind.name)
                            .put("createdAt", memory.createdAt)
                            .put("useCount", memory.useCount)
                            .put("lastUsedAt", memory.lastUsedAt)
                    )
                }
                file.writeText(array.toString())
            }.onFailure { AppLogger.e(TAG, "Couldn't save memories", it) }
        }
    }

    companion object {
        private const val FILE_NAME = "jarvis_memories.json"
        private const val MAX_MEMORIES = 400

        private val STOP_WORDS = setOf(
            "the", "and", "for", "are", "but", "not", "you", "your", "who", "was", "were",
            "with", "that", "this", "have", "has", "had", "what", "when", "where", "would",
            "could", "should", "about", "there", "their", "them", "they", "from", "into",
            "can", "will", "just", "like", "get", "got", "how", "why", "did", "does", "any",
        )

        /**
         * Process-wide singleton: tool implementations
         * ([com.jarvis.assistant.tools.JarvisTools]) are constructed inside the
         * inference engine and can't be handed a ViewModel-owned instance.
         */
        @Volatile
        private var instance: MemoryStore? = null

        fun get(context: Context): MemoryStore =
            instance ?: synchronized(this) {
                instance ?: MemoryStore(context).also { instance = it }
            }
    }
}
