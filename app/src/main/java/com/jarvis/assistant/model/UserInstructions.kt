package com.jarvis.assistant.model

import android.content.Context
import org.json.JSONArray

/**
 * The user's own standing instructions plus anything they've taught Jarvis
 * — appended to whichever persona's system prompt is active, on every
 * backend. This is the "teach / instruct / learn" surface: [customInstructions]
 * is free text the user writes in Settings, while [facts] accumulates
 * one-liners the model saves for itself when the user says "remember that…"
 * (see the rememberFact tool in `tools/JarvisTools.kt`).
 *
 * Deliberately simple persistence — SharedPreferences with a JSON array —
 * because this is at most a few dozen short strings, and it needs to be
 * readable synchronously from prompt-assembly code on any thread.
 */
class UserInstructions(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Free-text standing instructions ("always answer in metric", "I'm a nurse"). */
    var customInstructions: String
        get() = prefs.getString(KEY_CUSTOM, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM, value.trim()).apply()

    /** Short facts the user has taught Jarvis, newest last. */
    var facts: List<String>
        get() = runCatching {
            val array = JSONArray(prefs.getString(KEY_FACTS, "[]"))
            (0 until array.length()).map { array.getString(it) }
        }.getOrDefault(emptyList())
        set(value) {
            val trimmed = value.map { it.trim() }.filter { it.isNotBlank() }.takeLast(MAX_FACTS)
            prefs.edit().putString(KEY_FACTS, JSONArray(trimmed).toString()).apply()
        }

    /** Returns false if the fact was already known (case-insensitive). */
    fun addFact(fact: String): Boolean {
        val clean = fact.trim()
        if (clean.isBlank()) return false
        val existing = facts
        if (existing.any { it.equals(clean, ignoreCase = true) }) return false
        facts = existing + clean
        return true
    }

    fun removeFact(fact: String) {
        facts = facts.filterNot { it == fact }
    }

    fun clearFacts() {
        facts = emptyList()
    }

    /**
     * The block appended to the persona system prompt. Empty string when the
     * user hasn't set anything, so prompts stay clean by default.
     */
    fun promptBlock(): String {
        val instructions = customInstructions
        val knownFacts = facts
        if (instructions.isBlank() && knownFacts.isEmpty()) return ""
        return buildString {
            append("\n\n")
            if (instructions.isNotBlank()) {
                appendLine("Standing instructions from the user — follow these in every reply:")
                appendLine(instructions)
            }
            if (knownFacts.isNotEmpty()) {
                appendLine()
                appendLine("Things the user has taught you. Treat these as true and use them when relevant:")
                knownFacts.forEach { appendLine("- $it") }
            }
        }.trimEnd()
    }

    companion object {
        private const val PREFS_NAME = "jarvis_user_instructions"
        private const val KEY_CUSTOM = "custom_instructions"
        private const val KEY_FACTS = "facts"
        private const val MAX_FACTS = 100

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
