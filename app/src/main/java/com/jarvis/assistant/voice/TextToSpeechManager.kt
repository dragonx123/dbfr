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

    // Explicit type annotation needed: the init lambda below references `tts` on
    // itself (to set the language once ready), and without a declared type here
    // that self-reference sends the compiler into a recursive type-inference loop
    // ("Unresolved reference 'language'") trying to resolve tts's type from an
    // initializer that itself depends on tts's type.
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        isReady = status == TextToSpeech.SUCCESS
        if (isReady) {
            tts.language = Locale.getDefault()
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
     * Steers the TTS engine towards a voice matching [gender]. Most engines
     * (including the stock Google one) name some of their voices with a
     * "female"/"male" hint, e.g. "en-us-x-sfg#female_1-local" — when one is
     * found for the current locale it's selected directly. Otherwise this
     * falls back to a pitch shift, which reliably differentiates the two on
     * every device/engine even when no separate voices are installed.
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
        } else {
            tts.setPitch(if (gender == VoiceGender.FEMALE) 1.15f else 0.92f)
        }
    }

    private fun findVoiceForGender(gender: VoiceGender): Voice? {
        // Note: "female" contains "male" as a substring, so a plain
        // name.contains("male") check would wrongly match female voices too —
        // the male branch explicitly excludes "female" names to avoid that.
        val locale = Locale.getDefault()
        return runCatching {
            tts.voices
                ?.filter { !it.isNetworkConnectionRequired }
                ?.filter { voice ->
                    val name = voice.name.lowercase()
                    when (gender) {
                        VoiceGender.FEMALE -> name.contains("female")
                        VoiceGender.MALE -> name.contains("male") && !name.contains("female")
                    }
                }
                ?.sortedByDescending { it.locale.language == locale.language }
                ?.firstOrNull()
        }.getOrNull()
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
