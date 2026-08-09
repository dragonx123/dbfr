package com.jarvis.assistant.model

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.jarvis.assistant.util.AppLogger

private const val TAG = "BackendSettings"

/** Which [com.jarvis.assistant.ai.ChatBackend] Jarvis should use. */
enum class BackendType { ON_DEVICE, OLLAMA, CLOUD_API }

/** Which hosted API the CLOUD_API backend talks to. */
enum class CloudProvider { GEMINI, ANTHROPIC, OPENAI_COMPAT }

/** Everything the Settings screen saves in one shot. */
data class BackendConfig(
    val type: BackendType,
    val ollamaBaseUrl: String,
    val ollamaModel: String,
    val cloudProvider: CloudProvider,
    val cloudApiKey: String,
    val cloudModel: String,
    val cloudBaseUrl: String,
    val persona: Persona,
)

/** Small persisted settings for picking/configuring the chat backend. */
class BackendSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * The API key lives in EncryptedSharedPreferences (AES-256, master key in
     * the Android Keystore). Null when the keystore is unusable.
     *
     * There is deliberately no plaintext fallback. An earlier version fell
     * back to ordinary app-private prefs so cloud backends kept working on
     * such devices — but that silently downgraded the user's own API key to
     * plaintext without telling them, which is not a tradeoff the app gets
     * to make on their behalf. Now the key is simply not stored and the
     * cloud backend refuses to connect with a clear reason.
     */
    private val securePrefs: SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.onFailure {
        AppLogger.e(TAG, "Encrypted storage unavailable — cloud API keys cannot be saved", it)
    }.getOrNull()

    /** False when this device can't encrypt at rest, so no key can be stored. */
    val secureStorageAvailable: Boolean get() = securePrefs != null

    init {
        // Builds between the fallback landing and this fix could have written
        // a key into plain prefs. Move it into encrypted storage if we can,
        // and scrub it either way — leaving a plaintext key behind would
        // defeat the point of refusing to write one now.
        val legacy = prefs.getString(KEY_CLOUD_API_KEY, null)
        if (!legacy.isNullOrBlank()) {
            securePrefs?.edit()?.putString(KEY_CLOUD_API_KEY, legacy)?.apply()
            prefs.edit().remove(KEY_CLOUD_API_KEY).apply()
            AppLogger.w(
                TAG,
                if (securePrefs != null) "Migrated a plaintext API key into encrypted storage"
                else "Removed a plaintext API key; re-enter it once encrypted storage works",
            )
        }

        // Voices used to be chosen per gender rather than per persona. Seed
        // each persona from whichever gender voice it would have used, so a
        // user who already picked voices doesn't find them reset.
        val legacyMale = prefs.getString(KEY_LEGACY_MALE_VOICE, null)
        val legacyFemale = prefs.getString(KEY_LEGACY_FEMALE_VOICE, null)
        if (!legacyMale.isNullOrBlank() || !legacyFemale.isNullOrBlank()) {
            Personas.all.forEach { persona ->
                if (voiceNameFor(persona.id).isNotBlank()) return@forEach
                val inherited = when (persona.gender) {
                    VoiceGender.MALE -> legacyMale
                    VoiceGender.FEMALE -> legacyFemale
                }
                if (!inherited.isNullOrBlank()) setVoiceName(persona.id, inherited)
            }
            prefs.edit()
                .remove(KEY_LEGACY_MALE_VOICE)
                .remove(KEY_LEGACY_FEMALE_VOICE)
                .apply()
            AppLogger.i(TAG, "Migrated gender voice picks to per-persona voices")
        }
    }

    var backendType: BackendType
        get() = runCatching {
            BackendType.valueOf(prefs.getString(KEY_BACKEND_TYPE, BackendType.ON_DEVICE.name)!!)
        }.getOrDefault(BackendType.ON_DEVICE)
        set(value) = prefs.edit().putString(KEY_BACKEND_TYPE, value.name).apply()

    /** Base URL of the Ollama server, e.g. "http://192.168.1.50:11434". */
    var ollamaBaseUrl: String
        get() = prefs.getString(KEY_OLLAMA_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OLLAMA_URL, value.trim()).apply()

    /** Model name as known to that Ollama server, e.g. "llama3.2". */
    var ollamaModel: String
        get() = prefs.getString(KEY_OLLAMA_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OLLAMA_MODEL, value.trim()).apply()

    var cloudProvider: CloudProvider
        get() = runCatching {
            CloudProvider.valueOf(prefs.getString(KEY_CLOUD_PROVIDER, CloudProvider.GEMINI.name)!!)
        }.getOrDefault(CloudProvider.GEMINI)
        set(value) = prefs.edit().putString(KEY_CLOUD_PROVIDER, value.name).apply()

    /**
     * Reads as empty and refuses to store when encrypted storage is
     * unavailable — see [secureStorageAvailable]. Callers surface that to
     * the user rather than quietly writing the key in the clear.
     */
    var cloudApiKey: String
        get() = securePrefs?.getString(KEY_CLOUD_API_KEY, "") ?: ""
        set(value) {
            val store = securePrefs
            if (store == null) {
                AppLogger.e(TAG, "Refusing to save an API key without encrypted storage")
                return
            }
            store.edit().putString(KEY_CLOUD_API_KEY, value.trim()).apply()
        }

    /** Model ID at the chosen provider, e.g. "gemini-2.0-flash". */
    var cloudModel: String
        get() = prefs.getString(KEY_CLOUD_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CLOUD_MODEL, value.trim()).apply()

    /** Custom base URL — only used by OPENAI_COMPAT (Groq, Mistral, etc.). */
    var cloudBaseUrl: String
        get() = prefs.getString(KEY_CLOUD_BASE_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CLOUD_BASE_URL, value.trim().trimEnd('/')).apply()

    /**
     * TTS voice the user picked for one persona, by
     * [android.speech.tts.Voice.getName]. Blank means "let the app choose",
     * which falls back to a gender-named voice and then to pitch shifting —
     * see `TextToSpeechManager.applyPersona`.
     */
    fun voiceNameFor(personaId: String): String =
        prefs.getString(KEY_VOICE_PREFIX + personaId, "") ?: ""

    fun setVoiceName(personaId: String, voiceName: String) {
        prefs.edit().putString(KEY_VOICE_PREFIX + personaId, voiceName).apply()
    }

    /** Every persona's chosen voice, for handing to the TTS layer in one go. */
    fun allVoiceOverrides(): Map<String, String> =
        Personas.all.mapNotNull { persona ->
            voiceNameFor(persona.id).takeIf { it.isNotBlank() }?.let { persona.id to it }
        }.toMap()

    /** Which [Persona] (name, voice gender, personality) is currently selected. */
    var persona: Persona
        get() = Personas.byId(prefs.getString(KEY_PERSONA_ID, Personas.JARVIS.id) ?: Personas.JARVIS.id)
        set(value) = prefs.edit().putString(KEY_PERSONA_ID, value.id).apply()

    fun applyConfig(config: BackendConfig) {
        backendType = config.type
        ollamaBaseUrl = config.ollamaBaseUrl
        ollamaModel = config.ollamaModel
        cloudProvider = config.cloudProvider
        cloudApiKey = config.cloudApiKey
        cloudModel = config.cloudModel
        cloudBaseUrl = config.cloudBaseUrl
        persona = config.persona
    }

    companion object {
        private const val PREFS_NAME = "jarvis_backend_settings"
        private const val SECURE_PREFS_NAME = "jarvis_secure_settings"
        private const val KEY_BACKEND_TYPE = "backend_type"
        private const val KEY_OLLAMA_URL = "ollama_base_url"
        private const val KEY_OLLAMA_MODEL = "ollama_model"
        private const val KEY_CLOUD_PROVIDER = "cloud_provider"
        private const val KEY_CLOUD_API_KEY = "cloud_api_key"
        private const val KEY_CLOUD_MODEL = "cloud_model"
        private const val KEY_CLOUD_BASE_URL = "cloud_base_url"
        private const val KEY_PERSONA_ID = "persona_id"
        private const val KEY_VOICE_PREFIX = "voice_"

        // Pre-per-persona keys, drained by the migration in init.
        private const val KEY_LEGACY_MALE_VOICE = "male_voice_name"
        private const val KEY_LEGACY_FEMALE_VOICE = "female_voice_name"

        /** Sensible default model per provider, prefer cheap+fast+multimodal. */
        fun defaultModelFor(provider: CloudProvider): String = when (provider) {
            CloudProvider.GEMINI -> "gemini-2.0-flash"
            CloudProvider.ANTHROPIC -> "claude-sonnet-4-5"
            CloudProvider.OPENAI_COMPAT -> "gpt-4o-mini"
        }

        fun defaultBaseUrlFor(provider: CloudProvider): String = when (provider) {
            CloudProvider.OPENAI_COMPAT -> "https://api.openai.com"
            else -> ""
        }
    }
}
