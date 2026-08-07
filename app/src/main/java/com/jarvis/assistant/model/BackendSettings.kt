package com.jarvis.assistant.model

import android.content.Context

/** Which [com.jarvis.assistant.ai.ChatBackend] Jarvis should use. */
enum class BackendType { ON_DEVICE, OLLAMA }

/** Small persisted settings for picking/configuring the chat backend. */
class BackendSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

    /** Which [Persona] (name, voice gender, personality) is currently selected. */
    var persona: Persona
        get() = Personas.byId(prefs.getString(KEY_PERSONA_ID, Personas.JARVIS.id) ?: Personas.JARVIS.id)
        set(value) = prefs.edit().putString(KEY_PERSONA_ID, value.id).apply()

    companion object {
        private const val PREFS_NAME = "jarvis_backend_settings"
        private const val KEY_BACKEND_TYPE = "backend_type"
        private const val KEY_OLLAMA_URL = "ollama_base_url"
        private const val KEY_OLLAMA_MODEL = "ollama_model"
        private const val KEY_PERSONA_ID = "persona_id"
    }
}
