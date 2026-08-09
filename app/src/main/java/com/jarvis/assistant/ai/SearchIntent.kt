package com.jarvis.assistant.ai

/**
 * Recognises when the user has plainly asked for a web search, so the app
 * can run one instead of hoping the model calls a tool.
 *
 * The on-device model has `searchWebForAnswer` and still answered "search up
 * the moon" by repeating an unrelated earlier reply. Small models are
 * unreliable at tool invocation, and "search up X" is about as explicit as
 * an instruction gets — when the user has said the quiet part out loud,
 * guessing isn't required. Same backstop philosophy as
 * `memory.MemoryExtractor`.
 *
 * Deliberately narrow: only phrasings that unambiguously request a lookup.
 * A missed match just means the model handles it as before; a false match
 * would spend a network round-trip and paste irrelevant results into the
 * conversation.
 */
object SearchIntent {

    private val patterns = listOf(
        Regex("""^\s*(?:can you\s+|please\s+)?search\s+(?:up|for|the web for)\s+(.{2,120})""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(?:can you\s+|please\s+)?(?:google|bing|duckduckgo)\s+(.{2,120})""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(?:can you\s+|please\s+)?look\s+up\s+(.{2,120})""", RegexOption.IGNORE_CASE),
        Regex("""^\s*what'?s?\s+the\s+latest\s+(?:on|about|with)\s+(.{2,120})""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(?:can you\s+|please\s+)?find\s+(?:me\s+)?(?:info|information|news)\s+(?:on|about)\s+(.{2,120})""", RegexOption.IGNORE_CASE),
    )

    /** The thing to search for, or null when this isn't a search request. */
    fun queryFrom(message: String): String? {
        val text = message.trim()
        if (text.length < 4) return null
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val query = match.groupValues[1]
                .trim()
                .trimEnd('.', '!', '?', ',')
                // "search up the moon for me" -> "the moon"
                .removeSuffix(" for me")
                .trim()
            if (query.length >= 2) return query
        }
        return null
    }
}
