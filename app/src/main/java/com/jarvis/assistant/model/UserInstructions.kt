package com.jarvis.assistant.model

import android.content.Context
import org.json.JSONArray

/**
 * The user's standing instructions: free text they write in Settings, which
 * is appended to whichever persona's system prompt is active, on every
 * backend. This is the "how should you behave" half of teaching Jarvis.
 *
 * The "what do you know about me" half is
 * [com.jarvis.assistant.memory.MemoryStore] — that one is retrieved
 * per-message rather than pinned into every prompt, since it grows without
 * bound and only a few entries matter to any given turn.
 */
class UserInstructions(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Free-text standing instructions ("always answer in metric", "I'm a nurse"). */
    var customInstructions: String
        get() = prefs.getString(KEY_CUSTOM, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM, value.trim()).apply()

    /**
     * Facts saved before memory moved to `MemoryStore`, drained on first run
     * so nothing the user taught the previous build is lost. Returns them and
     * clears the old key; empty on every subsequent launch.
     */
    fun drainLegacyFacts(): List<String> {
        val stored = prefs.getString(KEY_FACTS, null) ?: return emptyList()
        val facts = runCatching {
            val array = JSONArray(stored)
            (0 until array.length()).map { array.getString(it) }
        }.getOrDefault(emptyList())
        prefs.edit().remove(KEY_FACTS).apply()
        return facts
    }

    /**
     * The block appended to the persona system prompt. Empty string when the
     * user hasn't set anything, so prompts stay clean by default.
     */
    fun promptBlock(): String {
        val instructions = customInstructions
        if (instructions.isBlank()) return ""
        return "\n\nStanding instructions from the user — follow these in every reply:\n$instructions"
    }

    companion object {
        private const val PREFS_NAME = "jarvis_user_instructions"
        private const val KEY_CUSTOM = "custom_instructions"
        private const val KEY_FACTS = "facts"

        /**
         * Process-wide singleton. Tool implementations ([com.jarvis.assistant.tools.JarvisTools])
         * are constructed deep inside the inference engine and can't easily be
         * handed a ViewModel-owned instance, so they reach this the same way
         * `AppLogger` is reached.
         */
        @Volatile
        private var instance: UserInstructions? = null

        fun get(context: Context): UserInstructions =
            instance ?: synchronized(this) {
                instance ?: UserInstructions(context).also { instance = it }
            }
    }
}
