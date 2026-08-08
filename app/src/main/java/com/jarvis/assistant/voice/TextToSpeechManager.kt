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
import kotlin.random.Random

private const val TAG = "TextToSpeech"

/** Speaks Jarvis's replies aloud using the on-device Android TTS engine. */
class TextToSpeechManager(context: Context) {

    private var isReady = false
    private var pendingText: String? = null
    private var pendingPersona: Persona? = null
    private var onSpeakingChanged: (Boolean) -> Unit = {}

    // The persona currently applied, and whether a real gender-matched voice
    // was found for it — [speak]'s per-sentence wobble needs both so it can
    // center itself on the pitch/rate baseline that's actually playing.
    private var activePersona: Persona? = null
    private var usingMatchedVoice = false

    /** ID of the final queued utterance of the current reply; see the progress listener. */
    private var lastUtteranceId: String? = null

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

            // A reply is spoken as several queued sentence utterances (see
            // [speak]), so "done" only means done when the LAST one of the
            // batch finishes — otherwise the mic would re-arm mid-sentence.
            override fun onDone(utteranceId: String?) {
                if (utteranceId == null || utteranceId == lastUtteranceId) onSpeakingChanged(false)
            }

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
        activePersona = persona
        val matched = findVoiceForGender(persona.gender)
        usingMatchedVoice = matched != null
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

    /**
     * Speaks [text] as a series of queued sentence-sized utterances rather
     * than one long block, with a small random pitch/rate wobble around the
     * active persona's baseline on each. A single flat utterance is what
     * makes long TTS replies drone; per-sentence variation reads as someone
     * actually talking. The wobble is deliberately small (±4%) — enough to
     * animate the delivery, not enough to sound like a different character
     * mid-reply.
     */
    fun speak(text: String) {
        if (text.isBlank()) return
        if (!isReady) {
            pendingText = text
            return
        }
        val sentences = splitIntoSentences(text)
        if (sentences.isEmpty()) return

        val basePitch = activePersona?.voicePitch ?: 1f
        val baseRate = activePersona?.voiceRate ?: 1f
        // If a gender-matched voice is in use, applyPersona() halved the
        // persona shift; mirror that here so the wobble stays centered on
        // whatever baseline is actually playing.
        val strength = if (usingMatchedVoice) 0.5f else 1f
        val centerPitch = 1f + (basePitch - 1f) * strength
        val centerRate = 1f + (baseRate - 1f) * strength

        sentences.forEachIndexed { index, sentence ->
            val id = UUID.randomUUID().toString()
            lastUtteranceId = id
            tts.setPitch((centerPitch * (0.96f + Random.nextFloat() * 0.08f)).coerceIn(0.5f, 2f))
            tts.setSpeechRate((centerRate * (0.97f + Random.nextFloat() * 0.06f)).coerceIn(0.5f, 2f))
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(sentence, mode, null, id)
        }
    }

    /**
     * Splits on sentence-ending punctuation, then merges any fragment under
     * ~25 characters into the next one — otherwise "Yes." or "OK." become
     * their own utterances and the queue gaps make the delivery stutter.
     */
    private fun splitIntoSentences(text: String): List<String> {
        val rough = Regex("(?<=[.!?])\\s+").split(text.trim()).filter { it.isNotBlank() }
        if (rough.size <= 1) return rough
        val merged = mutableListOf<String>()
        var buffer = StringBuilder()
        for (part in rough) {
            buffer.append(if (buffer.isEmpty()) part else " $part")
            if (buffer.length >= 25) {
                merged += buffer.toString()
                buffer = StringBuilder()
            }
        }
        if (buffer.isNotEmpty()) {
            if (merged.isEmpty()) merged += buffer.toString()
            else merged[merged.lastIndex] = merged.last() + " " + buffer.toString()
        }
        return merged
    }

    fun stop() {
        lastUtteranceId = null
        tts.stop()
        onSpeakingChanged(false)
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
