package com.jarvis.assistant.model

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

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

    // The API key lives in EncryptedSharedPreferences (AES-256, key in the
    // Android Keystore) rather than plain prefs. If the keystore is broken on
    // some device (rare but real), fall back to app-private plain prefs
    // rather than making cloud backends unusable.
    private val securePrefs: SharedPreferences = runCatching {
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
    }.getOrDefault(prefs)

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

    var cloudApiKey: String
        get() = securePrefs.getString(KEY_CLOUD_API_KEY, "") ?: ""
        set(value) = securePrefs.edit().putString(KEY_CLOUD_API_KEY, value.trim()).apply()

    /** Model ID at the chosen provider, e.g. "gemini-2.0-flash". */
    var cloudModel: String
        get() = prefs.getString(KEY_CLOUD_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CLOUD_MODEL, value.trim()).apply()

    /** Custom base URL — only used by OPENAI_COMPAT (Groq, Mistral, etc.). */
    var cloudBaseUrl: String
        get() = prefs.getString(KEY_CLOUD_BASE_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CLOUD_BASE_URL, value.trim().trimEnd('/')).apply()

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
