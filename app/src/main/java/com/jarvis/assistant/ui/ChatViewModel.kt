package com.jarvis.assistant.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.ai.ChatBackend
import com.jarvis.assistant.ai.LiteRtChatBackend
import com.jarvis.assistant.ai.OllamaChatBackend
import com.jarvis.assistant.model.BackendSettings
import com.jarvis.assistant.model.BackendType
import com.jarvis.assistant.model.ChatMessage
import com.jarvis.assistant.model.ModelRepository
import com.jarvis.assistant.model.Persona
import com.jarvis.assistant.model.Sender
import com.jarvis.assistant.voice.SpeechToText
import com.jarvis.assistant.voice.TextToSpeechManager
import com.jarvis.assistant.voice.WakeWordEvents
import com.jarvis.assistant.voice.WakeWordService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface ModelState {
    data object NotSetUp : ModelState
    data class Importing(val bytesCopied: Long) : ModelState
    data object Loading : ModelState
    data object Ready : ModelState
    data class Error(val message: String) : ModelState
}

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val modelRepository = ModelRepository(application)
    private val backendSettings = BackendSettings(application)
    private val speechToText = SpeechToText(application)
    private val tts = TextToSpeechManager(application)

    private var backend: ChatBackend? = null

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _modelState = MutableStateFlow<ModelState>(ModelState.NotSetUp)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val _backendType = MutableStateFlow(backendSettings.backendType)
    val backendType: StateFlow<BackendType> = _backendType.asStateFlow()

    private val _ollamaBaseUrl = MutableStateFlow(backendSettings.ollamaBaseUrl)
    val ollamaBaseUrl: StateFlow<String> = _ollamaBaseUrl.asStateFlow()

    private val _ollamaModel = MutableStateFlow(backendSettings.ollamaModel)
    val ollamaModel: StateFlow<String> = _ollamaModel.asStateFlow()

    private val _persona = MutableStateFlow(backendSettings.persona)
    val persona: StateFlow<Persona> = _persona.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _ttsEnabled = MutableStateFlow(true)
    val ttsEnabled: StateFlow<Boolean> = _ttsEnabled.asStateFlow()

    private val _wakeWordEnabled = MutableStateFlow(false)
    val wakeWordEnabled: StateFlow<Boolean> = _wakeWordEnabled.asStateFlow()

    init {
        tts.setOnSpeakingChanged { speaking ->
            _isSpeaking.value = speaking
            // Reset to the persisted persona's voice after every utterance, so a
            // Settings preview (which temporarily swaps the voice) never leaks
            // into actual chat replies if the user backs out without saving.
            if (!speaking) tts.applyGender(backendSettings.persona.gender)
        }
        tts.applyGender(backendSettings.persona.gender)
        initializeBackend()

        viewModelScope.launch {
            WakeWordEvents.events.collect { command ->
                if (command.isNotBlank()) {
                    sendMessage(command)
                } else {
                    startVoiceInput()
                }
            }
        }
    }

    /**
     * Persists a new backend configuration and persona together, then
     * (re)connects once. Called from the Settings screen's Save button.
     */
    fun updateSettings(type: BackendType, ollamaBaseUrl: String, ollamaModel: String, newPersona: Persona) {
        backendSettings.backendType = type
        backendSettings.ollamaBaseUrl = ollamaBaseUrl
        backendSettings.ollamaModel = ollamaModel
        backendSettings.persona = newPersona
        _backendType.value = type
        _ollamaBaseUrl.value = backendSettings.ollamaBaseUrl
        _ollamaModel.value = backendSettings.ollamaModel
        _persona.value = newPersona
        tts.applyGender(newPersona.gender)
        initializeBackend()
    }

    /**
     * Speaks a short sample line so the user can preview a persona's voice
     * before selecting it. The voice is reset back to the persisted persona
     * once the utterance finishes (see the speaking-changed listener above),
     * so previewing never permanently changes the active voice unless saved.
     */
    fun previewVoice(previewPersona: Persona) {
        tts.applyGender(previewPersona.gender)
        tts.speak("Hello, I'm ${previewPersona.displayName}.")
    }

    private fun initializeBackend() {
        backend?.close()
        backend = null

        val systemInstruction = backendSettings.persona.systemInstruction

        when (backendSettings.backendType) {
            BackendType.ON_DEVICE -> {
                if (!modelRepository.hasModel()) {
                    _modelState.value = ModelState.NotSetUp
                    return
                }
                val path = modelRepository.currentModelPath()!!
                connectBackend(
                    LiteRtChatBackend(
                        getApplication(), path, modelRepository.engineCacheDir(), systemInstruction
                    )
                )
            }
            BackendType.OLLAMA -> {
                val url = backendSettings.ollamaBaseUrl
                val model = backendSettings.ollamaModel
                if (url.isBlank() || model.isBlank()) {
                    _modelState.value = ModelState.NotSetUp
                    return
                }
                connectBackend(OllamaChatBackend(url, model, systemInstruction))
            }
        }
    }

    private fun connectBackend(newBackend: ChatBackend) {
        viewModelScope.launch {
            _modelState.value = ModelState.Loading
            runCatching {
                newBackend.initialize()
            }.onSuccess {
                backend = newBackend
                _modelState.value = ModelState.Ready
                val name = backendSettings.persona.displayName
                postMessage(Sender.SYSTEM, "$name is ready. Ask me anything, or tell me to do something on your phone.")
            }.onFailure {
                _modelState.value = ModelState.Error(it.message ?: "Failed to connect")
            }
        }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _modelState.value = ModelState.Importing(0)
            val result = modelRepository.importModel(uri) { bytes ->
                _modelState.value = ModelState.Importing(bytes)
            }
            result.onSuccess { initializeBackend() }
                .onFailure { _modelState.value = ModelState.Error(it.message ?: "Import failed") }
        }
    }

    fun sendMessage(text: String) {
        val activeBackend = backend
        if (text.isBlank() || activeBackend == null || _modelState.value !is ModelState.Ready) return

        postMessage(Sender.USER, text)
        val replyId = UUID.randomUUID().toString()
        _messages.value = _messages.value + ChatMessage(replyId, Sender.JARVIS, "", isStreaming = true)

        viewModelScope.launch {
            _isGenerating.value = true
            val builder = StringBuilder()
            runCatching {
                activeBackend.sendMessageStream(text).collect { chunk ->
                    builder.append(chunk)
                    updateMessage(replyId, builder.toString(), isStreaming = true)
                }
            }.onFailure {
                builder.append("\n\n[Error: ${it.message}]")
            }
            _isGenerating.value = false
            updateMessage(replyId, builder.toString(), isStreaming = false)
            if (_ttsEnabled.value) tts.speak(builder.toString())
        }
    }

    fun startVoiceInput() {
        if (!speechToText.isAvailable()) {
            postMessage(Sender.SYSTEM, "Speech recognition isn't available on this device.")
            return
        }
        speechToText.startListening(
            onListeningChanged = { _isListening.value = it },
            onFinalResult = { text -> sendMessage(text) },
            onError = { message -> if (_isListening.value) postMessage(Sender.SYSTEM, message) },
        )
    }

    fun stopVoiceInput() {
        speechToText.stopListening()
        _isListening.value = false
    }

    fun toggleTts() {
        _ttsEnabled.value = !_ttsEnabled.value
        if (!_ttsEnabled.value) tts.stop()
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        _wakeWordEnabled.value = enabled
        val app = getApplication<Application>()
        val serviceIntent = Intent(app, WakeWordService::class.java)
        if (enabled) app.startForegroundService(serviceIntent) else app.stopService(serviceIntent)
    }

    private fun postMessage(sender: Sender, text: String) {
        _messages.value = _messages.value + ChatMessage(UUID.randomUUID().toString(), sender, text)
    }

    private fun updateMessage(id: String, text: String, isStreaming: Boolean) {
        _messages.value = _messages.value.map {
            if (it.id == id) it.copy(text = text, isStreaming = isStreaming) else it
        }
    }

    override fun onCleared() {
        super.onCleared()
        speechToText.stopListening()
        tts.shutdown()
        backend?.close()
    }
}
