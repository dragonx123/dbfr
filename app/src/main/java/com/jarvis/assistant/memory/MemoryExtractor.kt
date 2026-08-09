package com.jarvis.assistant.memory

/**
 * Pulls things worth remembering out of what the user just said.
 *
 * This is a deliberate backstop, not the primary path: the model can save
 * memories itself (the `rememberFact` tool on-device, the `remember`
 * directive on the text backends). But the small on-device models this app
 * targets are unreliable about calling tools, and a memory that only works
 * on a paid cloud backend isn't much of a memory. These patterns catch the
 * explicit cases ("remember that…") and the common self-description ones
 * without needing the model's cooperation at all.
 *
 * Everything here is conservative — a missed memory is invisible, a wrong
 * one gets recited back at the user later and is actively annoying.
 */
object MemoryExtractor {

    private data class Pattern(val regex: Regex, val kind: MemoryKind, val template: (MatchResult) -> String?)

    private val patterns = listOf(
        // Explicit: "remember that I hate cilantro", "don't forget my wifi password is..."
        Pattern(
            Regex("""\b(?:remember|don'?t forget)\s+(?:that\s+)?(.{4,140})""", RegexOption.IGNORE_CASE),
            MemoryKind.FACT,
        ) { it.groupValues[1].trimEnd('.', '!', '?', ',').takeIf { s -> s.isNotBlank() } },

        // "my name is Greg", "my dog's name is Rosie", "my anniversary is June 4th"
        Pattern(
            Regex("""\bmy\s+([a-z'’\s]{2,30}?)\s+(?:is|are)\s+(.{2,80})""", RegexOption.IGNORE_CASE),
            MemoryKind.FACT,
        ) { match ->
            val subject = match.groupValues[1].trim()
            val value = match.groupValues[2].trimEnd('.', '!', '?', ',').trim()
            if (subject.isBlank() || value.isBlank()) null else "The user's $subject is $value"
        },

        // "I'm allergic to peanuts", "I am a paramedic"
        Pattern(
            Regex("""\bI(?:'m|’m|\s+am)\s+((?:allergic|a|an)\s+.{2,80})""", RegexOption.IGNORE_CASE),
            MemoryKind.FACT,
        ) { match ->
            val rest = match.groupValues[1].trimEnd('.', '!', '?', ',').trim()
            if (rest.isBlank()) null else "The user is $rest"
        },

        // "I work at Acme", "I live in Denver", "I drive a Tacoma"
        Pattern(
            Regex("""\bI\s+(work\s+(?:at|as|for)|live\s+in|drive\s+a|study)\s+(.{2,60})""", RegexOption.IGNORE_CASE),
            MemoryKind.FACT,
        ) { match ->
            val verb = match.groupValues[1].trim()
            val rest = match.groupValues[2].trimEnd('.', '!', '?', ',').trim()
            if (rest.isBlank()) null else "The user ${verb}s $rest".replace("works ats", "works at")
        },

        // Preferences: "I prefer dark roast", "I hate long answers"
        Pattern(
            Regex("""\bI\s+(prefer|always|never|usually|hate|love)\s+(.{3,90})""", RegexOption.IGNORE_CASE),
            MemoryKind.PREFERENCE,
        ) { match ->
            val verb = match.groupValues[1].lowercase()
            val rest = match.groupValues[2].trimEnd('.', '!', '?', ',').trim()
            if (rest.isBlank()) null else "The user $verb $rest"
        },
    )

    /**
     * Phrases that make a sentence a question, a hypothetical, or someone
     * else's statement — none of which should be stored as a fact about
     * the user. "Do you remember what I said?" must not become a memory.
     */
    private val disqualifiers = listOf(
        "?", "did you", "do you", "can you", "could you", "would you", "what if",
        "i think", "i'm not sure", "i am not sure", "maybe", "i guess", "pretend",
        "for example", "hypothetically", "someone said", "he said", "she said",
    )

    /**
     * Returns memories worth saving from [userMessage]. Usually empty —
     * most turns contain nothing durable, which is the point.
     */
    fun extract(userMessage: String): List<Pair<String, MemoryKind>> {
        val text = userMessage.trim()
        if (text.length < 8) return emptyList()

        val lower = text.lowercase()
        val explicitlyAsked = lower.contains("remember") || lower.contains("don't forget") ||
            lower.contains("don’t forget")

        // A question is never a statement of fact — except "remember that…",
        // which people often phrase with a trailing question mark.
        if (!explicitlyAsked && disqualifiers.any { lower.contains(it) }) return emptyList()

        val results = mutableListOf<Pair<String, MemoryKind>>()
        for (pattern in patterns) {
            val match = pattern.regex.find(text) ?: continue
            val extracted = pattern.template(match)?.trim() ?: continue
            if (extracted.length < 5 || extracted.length > 180) continue
            if (results.any { it.first.equals(extracted, ignoreCase = true) }) continue
            results += extracted to pattern.kind
            // One memory per message keeps this from turning a chatty
            // paragraph into six overlapping half-facts.
            break
        }
        return results
    }
}
