package com.jarvis.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.jarvis.assistant.model.VoiceGender
import java.util.Locale
import java.util.UUID

/** Speaks Jarvis's replies aloud using the on-device Android TTS engine. */
class TextToSpeechManager(context: Context) {

    private var isReady = false
    private var pendingText: String? = null
    private var pendingGender: VoiceGender? = null
    private var onSpeakingChanged: (Boolean) -> Unit = {}

    // The engine's own default voice, captured once at startup before any
    // gender is applied. Needed so the pitch-only fallback in [applyGender]
    // can reset back to a known starting point instead of leaving whatever
    // voice object a *previous* persona's gender match left selected.
    private var defaultVoice: Voice? = null

    // Explicit type annotation needed: the init lambda below references `tts` on
    // itself (to set the language once ready), and without a declared type here
    // that self-reference sends the compiler into a recursive type-inference loop
    // ("Unresolved reference 'language'") trying to resolve tts's type from an
    // initializer that itself depends on tts's type.
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        isReady = status == TextToSpeech.SUCCESS
        if (isReady) {
            tts.language = Locale.getDefault()
            defaultVoice = tts.voice
            pendingGender?.let { applyGender(it) }
            pendingText?.let { speak(it) }
            pendingText = null
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = onSpeakingChanged(true)
            override fun onDone(utteranceId: String?) = onSpeakingChanged(false)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = onSpeakingChanged(false)
        })
    }

    fun setOnSpeakingChanged(listener: (Boolean) -> Unit) {
        onSpeakingChanged = listener
    }

    /**
     * Steers the TTS engine towards a voice matching [gender]. Some engines
     * name a few of their voices with a "female"/"male" hint, e.g.
     * "en-us-x-sfg#female_1-local" — when one is found for the current
     * locale it's selected directly and played at neutral pitch/rate.
     *
     * Most modern devices (recent Pixels included) ship only a single local
     * voice per language with no gender in its name at all, so that match
     * usually fails — [findVoiceForGender] then falls back to picking a
     * *different* installed voice deterministically per gender if more than
     * one exists, and either way [applyGender] finishes with a strong
     * pitch/rate shift (not a token nudge) so personas are still clearly
     * distinguishable even when stuck sharing the one on-device voice.
     * Every call resets to [defaultVoice] first so a previous persona's
     * explicitly-matched voice never lingers onto the next one.
     */
    fun applyGender(gender: VoiceGender) {
        if (!isReady) {
            pendingGender = gender
            return
        }
        val matched = findVoiceForGender(gender)
        if (matched != null) {
            tts.voice = matched
            tts.setPitch(1.0f)
            tts.setSpeechRate(1.0f)
        } else {
            tts.voice = defaultVoice
            if (gender == VoiceGender.FEMALE) {
                tts.setPitch(1.25f)
                tts.setSpeechRate(1.05f)
            } else {
                tts.setPitch(0.78f)
                tts.setSpeechRate(0.95f)
            }
        }
    }

    private fun findVoiceForGender(gender: VoiceGender): Voice? {
        // Note: "female" contains "male" as a substring, so a plain
        // name.contains("male") check would wrongly match female voices too —
        // the male branch explicitly excludes "female" names to avoid that.
        val locale = Locale.getDefault()
        val candidates = runCatching {
            tts.voices
                ?.filter { it.locale.language == locale.language }
                ?.sortedByDescending { !it.isNetworkConnectionRequired } // local voices first
                ?: emptyList()
        }.getOrDefault(emptyList())

        val byName = candidates.firstOrNull { voice ->
            val name = voice.name.lowercase()
            when (gender) {
                VoiceGender.FEMALE -> name.contains("female")
                VoiceGender.MALE -> name.contains("male") && !name.contains("female")
            }
        }
        if (byName != null) return byName

        // No voice is gender-labeled (common on modern devices with just one
        // local voice per language). If there happen to be several distinct
        // *local* voices installed anyway, split them into two pools by name
        // so MALE and FEMALE personas at least land on genuinely different
        // voice models instead of the same one merely pitch-shifted — the
        // split is deterministic (sorted by name), so the same gender always
        // maps to the same voice across app runs.
        val localDistinct = candidates
            .filter { !it.isNetworkConnectionRequired }
            .distinctBy { it.name }
            .sortedBy { it.name }
        if (localDistinct.size < 2) return null
        val half = localDistinct.size / 2
        return when (gender) {
            VoiceGender.MALE -> localDistinct[0]
            VoiceGender.FEMALE -> localDistinct[half]
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        if (!isReady) {
            pendingText = text
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    fun stop() {
        tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
