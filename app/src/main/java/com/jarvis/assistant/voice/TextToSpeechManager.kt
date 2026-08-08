package com.jarvis.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.jarvis.assistant.model.Persona
import com.jarvis.assistant.model.VoiceGender
import com.jarvis.assistant.util.AppLogger
import java.util.Locale
import java.util.UUID

private const val TAG = "TextToSpeech"

/** Speaks Jarvis's replies aloud using the on-device Android TTS engine. */
class TextToSpeechManager(context: Context) {

    private var isReady = false
    private var pendingText: String? = null
    private var pendingPersona: Persona? = null
    private var onSpeakingChanged: (Boolean) -> Unit = {}

    // The engine's own default voice, captured once at startup before any
    // persona is applied. Needed so the pitch-only fallback in [applyPersona]
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
            AppLogger.i(TAG, "Engine ready, default voice=${defaultVoice?.name}")
            pendingPersona?.let { applyPersona(it) }
            pendingText?.let { speak(it) }
            pendingText = null
        } else {
            AppLogger.e(TAG, "Engine init failed (status=$status)")
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = onSpeakingChanged(true)
            override fun onDone(utteranceId: String?) = onSpeakingChanged(false)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                AppLogger.e(TAG, "Utterance error")
                onSpeakingChanged(false)
            }
        })
    }

    fun setOnSpeakingChanged(listener: (Boolean) -> Unit) {
        onSpeakingChanged = listener
    }

    /**
     * Steers the TTS engine towards [persona]'s voice: pick a voice matching
     * the persona's gender where the engine has one (some engines name their
     * voices with a "female"/"male" hint, e.g. "en-us-x-sfg#female_1-local"),
     * then apply the persona's own pitch/rate tuning on top so all five
     * personas sound distinct — even on modern devices (recent Pixels
     * included) that ship a single unlabeled local voice per language, where
     * the gender match fails and pitch/rate is all the differentiation there
     * is. When a gender-matched voice IS found, the persona shift is applied
     * at half strength (the voice itself already carries the gender).
     * Every call resets to [defaultVoice] first so a previous persona's
     * explicitly-matched voice never lingers onto the next one.
     */
    fun applyPersona(persona: Persona) {
        if (!isReady) {
            pendingPersona = persona
            return
        }
        val matched = findVoiceForGender(persona.gender)
        if (matched != null) {
            AppLogger.i(TAG, "applyPersona(${persona.id}): matched voice \"${matched.name}\"")
            tts.voice = matched
            tts.setPitch(1.0f + (persona.voicePitch - 1.0f) * 0.5f)
            tts.setSpeechRate(1.0f + (persona.voiceRate - 1.0f) * 0.5f)
        } else {
            AppLogger.i(TAG, "applyPersona(${persona.id}): no distinct voice, pitch=${persona.voicePitch} rate=${persona.voiceRate}")
            tts.voice = defaultVoice
            tts.setPitch(persona.voicePitch)
            tts.setSpeechRate(persona.voiceRate)
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
