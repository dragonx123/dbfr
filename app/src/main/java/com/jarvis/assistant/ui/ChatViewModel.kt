package com.jarvis.assistant.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.ai.JarvisEngine
import com.jarvis.assistant.model.ChatMessage
import com.jarvis.assistant.model.ModelRepository
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
    private val engine = JarvisEngine(application)
    private val speechToText = SpeechToText(application)
    private val tts = TextToSpeechManager(application)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _modelState = MutableStateFlow<ModelState>(ModelState.NotSetUp)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

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
        tts.setOnSpeakingChanged { _isSpeaking.value = it }

        if (modelRepository.hasModel()) {
            loadModel()
        }

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

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _modelState.value = ModelState.Importing(0)
            val result = modelRepository.importModel(uri) { bytes ->
                _modelState.value = ModelState.Importing(bytes)
            }
            result.onSuccess { loadModel() }
                .onFailure { _modelState.value = ModelState.Error(it.message ?: "Import failed") }
        }
    }

    private fun loadModel() {
        viewModelScope.launch {
            _modelState.value = ModelState.Loading
            val path = modelRepository.currentModelPath()
            if (path == null) {
                _modelState.value = ModelState.NotSetUp
                return@launch
            }
            runCatching {
                engine.initialize(path, modelRepository.engineCacheDir())
            }.onSuccess {
                _modelState.value = ModelState.Ready
                postMessage(Sender.SYSTEM, "Jarvis is ready. Ask me anything, or tell me to do something on your phone.")
            }.onFailure {
                _modelState.value = ModelState.Error(it.message ?: "Failed to load model")
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || _modelState.value !is ModelState.Ready) return

        postMessage(Sender.USER, text)
        val replyId = UUID.randomUUID().toString()
        _messages.value = _messages.value + ChatMessage(replyId, Sender.JARVIS, "", isStreaming = true)

        viewModelScope.launch {
            _isGenerating.value = true
            val builder = StringBuilder()
            runCatching {
                engine.sendMessageStream(text).collect { chunk ->
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
        engine.close()
    }
}
