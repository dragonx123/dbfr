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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

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

    // -- Full-screen voice mode --------------------------------------------------

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val _voiceModeActive = MutableStateFlow(false)
    private val _voiceModeMuted = MutableStateFlow(false)
    private val _voiceModeCooldown = MutableStateFlow(false)

    private val _voiceModePartialTranscript = MutableStateFlow("")
    val voiceModePartialTranscript: StateFlow<String> = _voiceModePartialTranscript.asStateFlow()

    private val _voiceModeError = MutableStateFlow<String?>(null)
    val voiceModeError: StateFlow<String?> = _voiceModeError.asStateFlow()

    val isVoiceModeMuted: StateFlow<Boolean> = _voiceModeMuted.asStateFlow()

    /** The last assistant reply's text, for the voice-mode caption while thinking/speaking. */
    val voiceModeReplyText: StateFlow<String> = messages
        .map { list -> list.lastOrNull { it.sender == Sender.JARVIS }?.text.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    /** Derived, not hand-set, so it can never drift from the flows it's built on. */
    private val voiceModeFlags = combine(_voiceModeActive, _voiceModeMuted, _voiceModeCooldown, ::Triple)

    val orbPhase: StateFlow<OrbPhase> = combine(
        voiceModeFlags, isListening, isGenerating, isSpeaking,
    ) { (active, muted, cooldown), listening, generating, speaking ->
        when {
            !active -> OrbPhase.IDLE
            muted -> OrbPhase.MUTED
            speaking -> OrbPhase.SPEAKING
            generating -> OrbPhase.THINKING
            cooldown -> OrbPhase.COOLDOWN
            listening -> OrbPhase.LISTENING
            else -> OrbPhase.IDLE
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OrbPhase.IDLE)

    private var voiceModeCooldownJob: Job? = null
    private var wasTtsEnabledBeforeVoiceMode = true
    private var wasWakeWordEnabledBeforeVoiceMode = false
    private var consecutiveVoiceModeErrors = 0

    // ------------------------------------------------------------------------------

    init {
        tts.setOnSpeakingChanged { speaking ->
            _isSpeaking.value = speaking
            if (!speaking) {
                // Reset to the persisted persona's voice after every utterance, so a
                // Settings preview (which temporarily swaps the voice) never leaks
                // into actual chat replies if the user backs out without saving.
                tts.applyGender(backendSettings.persona.gender)
                if (_voiceModeActive.value && !_voiceModeMuted.value) {
                    armMicAfterCooldown()
                }
            }
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

    // -- Voice mode ---------------------------------------------------------------

    /** Enters full-screen voice mode and starts listening for the first turn. */
    fun enterVoiceMode() {
        if (_voiceModeActive.value) return
        wasTtsEnabledBeforeVoiceMode = _ttsEnabled.value
        wasWakeWordEnabledBeforeVoiceMode = _wakeWordEnabled.value
        _ttsEnabled.value = true
        if (_wakeWordEnabled.value) {
            // Pause the background listener (without touching the user's saved
            // preference) so it doesn't fight the foreground recognizer for the mic.
            val app = getApplication<Application>()
            app.stopService(Intent(app, WakeWordService::class.java))
        }
        consecutiveVoiceModeErrors = 0
        _voiceModeError.value = null
        _voiceModeMuted.value = false
        _voiceModeActive.value = true
        startVoiceInputForVoiceMode()
    }

    /** Leaves voice mode, stops listening/speaking, and restores prior TTS/wake-word state. */
    fun exitVoiceMode() {
        if (!_voiceModeActive.value) return
        voiceModeCooldownJob?.cancel()
        voiceModeCooldownJob = null
        stopVoiceInput()
        tts.stop()
        _voiceModeActive.value = false
        _voiceModeMuted.value = false
        _voiceModeCooldown.value = false
        _voiceModePartialTranscript.value = ""
        _voiceModeError.value = null
        _ttsEnabled.value = wasTtsEnabledBeforeVoiceMode
        if (wasWakeWordEnabledBeforeVoiceMode) {
            val app = getApplication<Application>()
            app.startForegroundService(Intent(app, WakeWordService::class.java))
        }
    }

    /** Mutes (stops listening/interrupts speech) or unmutes (resumes listening) voice mode. */
    fun toggleVoiceModeMute() {
        if (!_voiceModeActive.value) return
        if (_voiceModeMuted.value) {
            _voiceModeMuted.value = false
            consecutiveVoiceModeErrors = 0
            _voiceModeError.value = null
            if (!_isGenerating.value && !_isSpeaking.value) startVoiceInputForVoiceMode()
        } else {
            _voiceModeMuted.value = true
            voiceModeCooldownJob?.cancel()
            _voiceModeCooldown.value = false
            stopVoiceInput()
            if (_isSpeaking.value) tts.stop()
        }
    }

    private fun armMicAfterCooldown() {
        voiceModeCooldownJob?.cancel()
        voiceModeCooldownJob = viewModelScope.launch {
            _voiceModeCooldown.value = true
            delay(VOICE_MODE_COOLDOWN_MS)
            _voiceModeCooldown.value = false
            if (_voiceModeActive.value && !_voiceModeMuted.value) {
                startVoiceInputForVoiceMode()
            }
        }
    }

    private fun startVoiceInputForVoiceMode() {
        if (!speechToText.isAvailable()) {
            _voiceModeError.value = "Speech recognition isn't available on this device."
            _voiceModeMuted.value = true
            return
        }
        _voiceModePartialTranscript.value = ""
        speechToText.startListening(
            onPartialResult = { text ->
                consecutiveVoiceModeErrors = 0
                _voiceModePartialTranscript.value = text
            },
            onListeningChanged = { _isListening.value = it },
            onFinalResult = { text ->
                consecutiveVoiceModeErrors = 0
                _voiceModePartialTranscript.value = ""
                sendMessage(text)
            },
            onError = { message ->
                _isListening.value = false
                if (!_voiceModeActive.value || _voiceModeMuted.value) return@startListening
                consecutiveVoiceModeErrors++
                if (consecutiveVoiceModeErrors >= MAX_CONSECUTIVE_VOICE_MODE_ERRORS) {
                    // Stop auto-retrying on a real, persistent problem (e.g. permission
                    // revoked) rather than looping forever — surface it and pause;
                    // the user can tap unmute once they've fixed it.
                    _voiceModeError.value = message
                    _voiceModeMuted.value = true
                } else {
                    startVoiceInputForVoiceMode()
                }
            },
            onRmsChanged = { rmsDb -> _micLevel.value = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f) },
        )
    }

    // -------------------------------------------------------------------------------

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

    @OptIn(FlowPreview::class)
    fun sendMessage(text: String) {
        val activeBackend = backend
        // Also refuse a new message while one is still generating: sendMessage
        // used to launch an independent coroutine per call with no guard, so
        // if a reply ever stalled (see the idle timeout below for why that can
        // happen), every message the user sent afterwards silently piled up
        // as its own overlapping request against the same Conversation
        // instead of surfacing the stall - which read as Jarvis going
        // permanently silent, stuck on a "..." bubble forever.
        if (text.isBlank() || activeBackend == null ||
            _modelState.value !is ModelState.Ready || _isGenerating.value
        ) return

        postMessage(Sender.USER, text)
        val replyId = UUID.randomUUID().toString()
        _messages.value = _messages.value + ChatMessage(replyId, Sender.JARVIS, "", isStreaming = true)

        viewModelScope.launch {
            _isGenerating.value = true
            val builder = StringBuilder()
            var stalled = false
            runCatching {
                activeBackend.sendMessageStream(text)
                    .timeout(GENERATION_IDLE_TIMEOUT)
                    .collect { chunk ->
                        builder.append(chunk)
                        updateMessage(replyId, builder.toString(), isStreaming = true)
                    }
            }.onFailure { error ->
                stalled = error is TimeoutCancellationException
                val reason = if (stalled) {
                    "Jarvis stopped responding. Reconnecting — try sending that again."
                } else {
                    "[Error: ${error.message}]"
                }
                builder.append(if (builder.isEmpty()) reason else "\n\n$reason")
            }
            _isGenerating.value = false
            updateMessage(replyId, builder.toString(), isStreaming = false)
            if (_ttsEnabled.value) tts.speak(builder.toString())
            // A stall likely means the underlying engine/conversation (most
            // plausible on-device, e.g. wedged mid tool-call) won't recover on
            // its own — reconnect so the next message gets a fresh one rather
            // than hanging again. Off the main dispatcher: initializeBackend()
            // synchronously closes the old engine/conversation, and a wedged
            // native call there shouldn't get a chance to freeze the UI too.
            if (stalled) withContext(Dispatchers.IO) { initializeBackend() }
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
        voiceModeCooldownJob?.cancel()
        speechToText.stopListening()
        tts.shutdown()
        backend?.close()
    }

    companion object {
        private const val VOICE_MODE_COOLDOWN_MS = 700L
        private const val MAX_CONSECUTIVE_VOICE_MODE_ERRORS = 3

        // How long to wait for the *next* chunk before giving up on a reply.
        // Some on-device generations can legitimately take a while (cold
        // start, long answers), so this is an idle timeout - it resets on
        // every chunk - not a hard cap on total response time.
        private val GENERATION_IDLE_TIMEOUT = 45.seconds
    }
}
