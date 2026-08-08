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
    private var onReady: () -> Unit = {}

    // The persona currently applied, and whether a real gender-matched voice
    // was found for it — [speak]'s per-sentence wobble needs both so it can
    // center itself on the pitch/rate baseline that's actually playing.
    private var activePersona: Persona? = null
    private var usingMatchedVoice = false

    /** Persona id -> voice name, for personas the user explicitly assigned. */
    private var voiceOverrides: Map<String, String> = emptyMap()

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
            // Listed in the Logs screen so a "why does everyone sound the
            // same" report can be answered from the device itself.
            val voices = usableVoices()
            AppLogger.i(TAG, "${voices.size} usable voices: ${voices.joinToString { it.name }}")
            pendingPersona?.let { applyPersona(it) }
            pendingText?.let { speak(it) }
            pendingText = null
            onReady()
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
     * Called once the engine has initialised. The voice list is empty until
     * then, so anything showing it (the Settings picker) has to wait for
     * this rather than reading at construction time and finding nothing.
     */
    fun setOnReady(listener: () -> Unit) {
        onReady = listener
        if (isReady) listener()
    }

    /**
     * Selects the voice for [persona] and applies its pitch/rate.
     *
     * Voice choice, in priority order:
     *  1. A voice the user picked themselves for this gender in Settings.
     *  2. A voice whose name genuinely advertises a gender, e.g.
     *     "en-us-x-sfg#female_1-local".
     *  3. The engine default, with pitch/rate doing all the work.
     *
     * There is deliberately no guessing beyond that. An earlier version
     * split the installed voices into two pools alphabetically and called
     * the halves "male" and "female" — but Google's voice names
     * ("en-us-x-iob-local") encode nothing about gender, so that assigned
     * arbitrary voices, sometimes ones that weren't downloaded and so were
     * silent, and worse, believing it had a gendered voice made it apply
     * only half the pitch correction. Male personas ended up on a
     * female-sounding voice at nearly normal pitch.
     *
     * The pitch shift is applied at full strength unless the voice itself
     * genuinely carries the gender, since on the many devices with a single
     * unlabeled voice it is the only differentiation available.
     */
    fun applyPersona(persona: Persona) {
        if (!isReady) {
            pendingPersona = persona
            return
        }
        activePersona = persona
        val matched = voiceFor(persona)
        usingMatchedVoice = matched != null

        val chosen = matched ?: defaultVoice
        if (chosen != null && !trySetVoice(chosen)) {
            // Selecting the voice failed — fall back to the engine default
            // rather than leaving a voice set that will silently not speak.
            AppLogger.w(TAG, "Voice \"${chosen.name}\" rejected, using engine default")
            defaultVoice?.let { trySetVoice(it) }
            usingMatchedVoice = false
        }

        val strength = if (usingMatchedVoice) 0.5f else 1f
        val pitch = 1f + (persona.voicePitch - 1f) * strength
        val rate = 1f + (persona.voiceRate - 1f) * strength
        tts.setPitch(pitch.coerceIn(0.4f, 2f))
        tts.setSpeechRate(rate.coerceIn(0.4f, 2f))
        AppLogger.i(
            TAG,
            "applyPersona(${persona.id}): voice=${chosen?.name ?: "default"} " +
                "genderMatched=$usingMatchedVoice pitch=$pitch rate=$rate",
        )
    }

    private fun trySetVoice(voice: Voice): Boolean =
        runCatching { tts.setVoice(voice) == TextToSpeech.SUCCESS }.getOrDefault(false)

    /**
     * The voice for [persona]: their own explicit pick if they have one,
     * otherwise a voice whose name genuinely advertises the right gender,
     * otherwise null so the caller falls back to the engine default.
     */
    private fun voiceFor(persona: Persona): Voice? {
        val chosen = voiceOverrides[persona.id]
        if (!chosen.isNullOrBlank()) {
            usableVoices().firstOrNull { it.name == chosen }?.let { return it }
            // The pick referenced a voice this device no longer has (uninstalled,
            // or the settings came from another phone) — fall through rather
            // than selecting nothing and going silent.
            AppLogger.w(TAG, "Voice \"$chosen\" for ${persona.id} is gone; falling back")
        }

        // Only trust a name that actually advertises a gender. Note "female"
        // contains "male", so the male branch has to exclude it explicitly.
        return usableVoices().firstOrNull { voice ->
            val name = voice.name.lowercase()
            when (persona.gender) {
                VoiceGender.FEMALE -> name.contains("female")
                VoiceGender.MALE -> name.contains("male") && !name.contains("female")
            }
        }
    }

    /**
     * Voices that can actually speak: right language, no network needed, and
     * crucially not flagged as not-installed. `tts.voices` happily lists
     * voices whose data hasn't been downloaded; selecting one of those makes
     * `speak()` fail silently, which is exactly how two personas ended up
     * mute with no error anywhere.
     */
    fun usableVoices(): List<Voice> {
        val language = Locale.getDefault().language
        return runCatching {
            tts.voices.orEmpty()
                .filter { voice ->
                    voice.locale.language == language &&
                        !voice.isNetworkConnectionRequired &&
                        voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
                }
                .distinctBy { it.name }
                .sortedBy { it.name }
        }.getOrDefault(emptyList())
    }

    /**
     * Persists nothing itself — callers pass the saved persona -> voice map
     * in on startup and whenever it changes.
     */
    fun setVoiceOverrides(overrides: Map<String, String>) {
        voiceOverrides = overrides
        activePersona?.let { applyPersona(it) }
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
