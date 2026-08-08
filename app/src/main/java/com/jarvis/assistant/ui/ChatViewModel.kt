package com.jarvis.assistant.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.ai.ChatBackend
import com.jarvis.assistant.ai.CloudApiChatBackend
import com.jarvis.assistant.ai.LiteRtChatBackend
import com.jarvis.assistant.ai.OllamaChatBackend
import com.jarvis.assistant.ai.ToolDirective
import com.jarvis.assistant.ai.WebTools
import com.jarvis.assistant.ai.parseToolDirective
import com.jarvis.assistant.control.JarvisAccessibilityService
import com.jarvis.assistant.control.ScreenCaptureManager
import com.jarvis.assistant.memory.ConversationStore
import com.jarvis.assistant.memory.Memory
import com.jarvis.assistant.memory.MemoryExtractor
import com.jarvis.assistant.memory.MemoryKind
import com.jarvis.assistant.memory.MemoryStore
import com.jarvis.assistant.model.BackendConfig
import com.jarvis.assistant.model.BackendSettings
import com.jarvis.assistant.model.BackendType
import com.jarvis.assistant.model.ChatMessage
import com.jarvis.assistant.model.CloudProvider
import com.jarvis.assistant.model.DataCard
import com.jarvis.assistant.model.DataCardEntry
import com.jarvis.assistant.model.ModelRepository
import com.jarvis.assistant.model.Persona
import com.jarvis.assistant.model.Sender
import com.jarvis.assistant.model.UserInstructions
import com.jarvis.assistant.util.AppLogger
import com.jarvis.assistant.voice.SpeechToText
import com.jarvis.assistant.voice.TextToSpeechManager
import com.jarvis.assistant.voice.VoiceSessionCommand
import com.jarvis.assistant.voice.VoiceSessionEvents
import com.jarvis.assistant.voice.VoiceSessionService
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

private const val TAG = "ChatViewModel"

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
    private val userInstructions = UserInstructions.get(application)
    private val memoryStore = MemoryStore.get(application)
    private val conversationStore = ConversationStore(application)
    private val speechToText = SpeechToText(application)
    private val tts = TextToSpeechManager(application)

    private var backend: ChatBackend? = null

    // Seeded from disk so closing the app no longer wipes the conversation.
    private val _messages = MutableStateFlow<List<ChatMessage>>(conversationStore.load())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    val memories: StateFlow<List<Memory>> = memoryStore.memories

    private val _modelState = MutableStateFlow<ModelState>(ModelState.NotSetUp)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val _backendType = MutableStateFlow(backendSettings.backendType)
    val backendType: StateFlow<BackendType> = _backendType.asStateFlow()

    private val _ollamaBaseUrl = MutableStateFlow(backendSettings.ollamaBaseUrl)
    val ollamaBaseUrl: StateFlow<String> = _ollamaBaseUrl.asStateFlow()

    private val _ollamaModel = MutableStateFlow(backendSettings.ollamaModel)
    val ollamaModel: StateFlow<String> = _ollamaModel.asStateFlow()

    private val _cloudProvider = MutableStateFlow(backendSettings.cloudProvider)
    val cloudProvider: StateFlow<CloudProvider> = _cloudProvider.asStateFlow()

    private val _cloudApiKey = MutableStateFlow(backendSettings.cloudApiKey)
    val cloudApiKey: StateFlow<String> = _cloudApiKey.asStateFlow()

    private val _cloudModel = MutableStateFlow(backendSettings.cloudModel)
    val cloudModel: StateFlow<String> = _cloudModel.asStateFlow()

    private val _cloudBaseUrl = MutableStateFlow(backendSettings.cloudBaseUrl)
    val cloudBaseUrl: StateFlow<String> = _cloudBaseUrl.asStateFlow()

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

    /** True between "user interrupted the reply" and that interruption being sent. */
    private var bargeInTriggered = false

    /**
     * A recap of the visible transcript, queued for the next message after a
     * (re)connect. Backends always start with empty history of their own, so
     * without this Jarvis loses the thread on every app restart and backend
     * switch even though the user can still see the conversation on screen.
     */
    private var pendingRecap = ""

    /** What Jarvis is currently saying aloud — used to reject the mic hearing itself. */
    private var currentlySpokenText = ""

    // ------------------------------------------------------------------------------

    init {
        // Note: these callbacks arrive on the TTS engine's own thread, and
        // SpeechRecognizer must be driven from the main thread — hence the
        // viewModelScope hop (Dispatchers.Main.immediate) before touching it.
        tts.setOnSpeakingChanged { speaking ->
            val wasSpeaking = _isSpeaking.value
            _isSpeaking.value = speaking
            viewModelScope.launch {
                when {
                    // A reply is several queued sentence utterances, so only the
                    // first onStart of a batch should arm the barge-in listener —
                    // restarting it per sentence would keep killing the
                    // recognition that's mid-way through capturing the user.
                    speaking && !wasSpeaking -> {
                        if (_voiceModeActive.value && !_voiceModeMuted.value) {
                            startVoiceInputForVoiceMode(bargeIn = true)
                        }
                    }
                    !speaking && wasSpeaking -> {
                        // Reset to the persisted persona's voice after every reply, so a
                        // Settings preview (which temporarily swaps the voice) never leaks
                        // into actual chat replies if the user backs out without saving.
                        tts.applyPersona(backendSettings.persona)
                        // After a barge-in the recognizer is already mid-utterance
                        // capturing the interruption; re-arming would cut it off.
                        if (_voiceModeActive.value && !_voiceModeMuted.value && !bargeInTriggered) {
                            armMicAfterCooldown()
                        }
                        bargeInTriggered = false
                    }
                }
            }
        }
        tts.applyPersona(backendSettings.persona)
        // One-time move of anything taught to the previous build, which kept
        // facts in SharedPreferences before MemoryStore existed.
        userInstructions.drainLegacyFacts().forEach { memoryStore.remember(it, MemoryKind.FACT) }
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

        // Mute/End buttons on the background voice-session notification.
        viewModelScope.launch {
            VoiceSessionEvents.events.collect { command ->
                when (command) {
                    VoiceSessionCommand.TOGGLE_MUTE -> toggleVoiceModeMute()
                    VoiceSessionCommand.END -> exitVoiceMode()
                }
            }
        }

        // Keep the background notification's text in step with the session.
        viewModelScope.launch {
            combine(orbPhase, isVoiceModeMuted) { phase, muted -> phase to muted }
                .collect { (phase, muted) ->
                    if (_voiceModeActive.value) updateVoiceSessionNotification(phase, muted)
                }
        }
    }

    /**
     * Persists a new backend configuration and persona together, then
     * (re)connects once. Called from the Settings screen's Save button.
     */
    fun updateSettings(config: BackendConfig) {
        AppLogger.i(TAG, "updateSettings: backend=${config.type} persona=${config.persona.id}")
        backendSettings.applyConfig(config)
        _backendType.value = backendSettings.backendType
        _ollamaBaseUrl.value = backendSettings.ollamaBaseUrl
        _ollamaModel.value = backendSettings.ollamaModel
        _cloudProvider.value = backendSettings.cloudProvider
        _cloudApiKey.value = backendSettings.cloudApiKey
        _cloudModel.value = backendSettings.cloudModel
        _cloudBaseUrl.value = backendSettings.cloudBaseUrl
        _persona.value = backendSettings.persona
        tts.applyPersona(backendSettings.persona)
        initializeBackend()
    }

    /**
     * Speaks a short sample line so the user can preview a persona's voice
     * before selecting it. The voice is reset back to the persisted persona
     * once the utterance finishes (see the speaking-changed listener above),
     * so previewing never permanently changes the active voice unless saved.
     */
    fun previewVoice(previewPersona: Persona) {
        tts.applyPersona(previewPersona)
        tts.speak(previewLine(previewPersona))
    }

    private fun previewLine(p: Persona): String = when (p.id) {
        "jarvis" -> "At your service, sir. Jarvis, online."
        "friday" -> "Hiya! Friday here — no bother at all."
        "edith" -> "Edith online. Scanning complete, all clear."
        "vision" -> "Hello. I am Vision. A pleasure, truly."
        "ultron" -> "Ultron. Try to make your requests interesting."
        else -> "Hello, I'm ${p.displayName}."
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
        // Foreground service with the microphone type: without it Android 12+
        // cuts the recognizer off as soon as the app stops being visible, so
        // this is what actually lets the conversation continue in the
        // background / with the screen off.
        val app = getApplication<Application>()
        runCatching { app.startForegroundService(Intent(app, VoiceSessionService::class.java)) }
            .onFailure { AppLogger.e(TAG, "Couldn't start background voice service", it) }
        startVoiceInputForVoiceMode()
    }

    /** Leaves voice mode, stops listening/speaking, and restores prior TTS/wake-word state. */
    fun exitVoiceMode() {
        if (!_voiceModeActive.value) return
        voiceModeCooldownJob?.cancel()
        voiceModeCooldownJob = null
        // Clear the active flag *before* stopping TTS: tts.stop() reports
        // "no longer speaking" synchronously, and the handler for that
        // re-arms the mic while voice mode still looks active.
        _voiceModeActive.value = false
        stopVoiceInput()
        tts.stop()
        _voiceModeMuted.value = false
        _voiceModeCooldown.value = false
        _voiceModePartialTranscript.value = ""
        _voiceModeError.value = null
        bargeInTriggered = false
        _ttsEnabled.value = wasTtsEnabledBeforeVoiceMode
        val app = getApplication<Application>()
        app.stopService(Intent(app, VoiceSessionService::class.java))
        if (wasWakeWordEnabledBeforeVoiceMode) {
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

    /**
     * Distinguishes the user actually cutting in from the microphone simply
     * picking up the phone's own speaker. Two cheap signals, no extra
     * hardware support needed (Android gives no "is this my own output"
     * API): an interruption is at least two words, and its words are not
     * mostly already present in whatever Jarvis is currently saying.
     */
    private fun isRealInterruption(text: String): Boolean {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 2) return false
        val spoken = currentlySpokenText.lowercase()
        if (spoken.isBlank()) return true
        val echoed = words.count { spoken.contains(it.lowercase()) }
        return echoed.toFloat() / words.size < 0.6f
    }

    private fun updateVoiceSessionNotification(phase: OrbPhase, muted: Boolean) {
        val label = when (phase) {
            OrbPhase.LISTENING -> "Listening…"
            OrbPhase.THINKING -> "Thinking…"
            OrbPhase.SPEAKING -> "Speaking…"
            OrbPhase.MUTED -> "Muted"
            OrbPhase.COOLDOWN, OrbPhase.IDLE -> "Ready"
        }
        val app = getApplication<Application>()
        runCatching {
            app.startService(
                Intent(app, VoiceSessionService::class.java)
                    .setAction(VoiceSessionService.ACTION_UPDATE_STATE)
                    .putExtra(VoiceSessionService.EXTRA_STATE_LABEL, label)
                    .putExtra(VoiceSessionService.EXTRA_MUTED, muted)
            )
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

    /**
     * Starts the voice-mode recognizer. With [bargeIn] the mic runs *while*
     * Jarvis is still speaking, so the user can cut in mid-sentence the way
     * they can with Gemini or ChatGPT voice; the first partial result that
     * looks like a genuine interruption (rather than the mic picking up the
     * phone's own speaker) stops playback and becomes the next turn.
     */
    private fun startVoiceInputForVoiceMode(bargeIn: Boolean = false) {
        if (!speechToText.isAvailable()) {
            _voiceModeError.value = "Speech recognition isn't available on this device."
            _voiceModeMuted.value = true
            return
        }
        _voiceModePartialTranscript.value = ""
        speechToText.startListening(
            onPartialResult = { text ->
                consecutiveVoiceModeErrors = 0
                if (bargeIn && _isSpeaking.value) {
                    if (isRealInterruption(text)) {
                        AppLogger.i(TAG, "Barge-in: \"$text\" — stopping playback")
                        bargeInTriggered = true
                        tts.stop()
                        _voiceModePartialTranscript.value = text
                    }
                    // Otherwise it's almost certainly the mic hearing the reply
                    // being spoken — don't show it as the user's transcript.
                } else {
                    _voiceModePartialTranscript.value = text
                }
            },
            onListeningChanged = { _isListening.value = it },
            onFinalResult = { text ->
                consecutiveVoiceModeErrors = 0
                _voiceModePartialTranscript.value = ""
                // A final result from the barge-in listener that never qualified
                // as an interruption is echo of Jarvis's own voice — dropping it
                // stops the assistant from answering itself in a loop.
                if (bargeIn && !bargeInTriggered && !isRealInterruption(text)) {
                    AppLogger.i(TAG, "Ignoring echo of spoken reply: \"${text.take(40)}\"")
                    return@startListening
                }
                bargeInTriggered = false
                if (_isSpeaking.value) tts.stop()
                // Continuous screen view: each spoken turn carries a fresh
                // frame, so Jarvis answers about whatever is on screen right
                // now without being asked to look each time.
                if (_continuousScreenView.value && ScreenCaptureManager.hasConsent) {
                    sendWithScreenshot(text)
                } else {
                    sendMessage(text)
                }
            },
            onError = { message ->
                _isListening.value = false
                if (!_voiceModeActive.value || _voiceModeMuted.value) return@startListening
                // "No speech" while Jarvis is still talking just means the user
                // didn't interrupt — expected, so restart quietly without
                // counting it toward the give-up threshold.
                if (bargeIn && _isSpeaking.value) {
                    startVoiceInputForVoiceMode(bargeIn = true)
                    return@startListening
                }
                AppLogger.w(TAG, "Voice mode speech error: $message")
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
        AppLogger.i(TAG, "initializeBackend: ${backendSettings.backendType}")
        backend?.close()
        backend = null

        // On-device uses LiteRT's real @Tool mechanism (JarvisTools); the
        // text-protocol backends get the JSON tool-directive loop instead —
        // see WEB_TOOL_INSTRUCTION and the loop in sendMessage().
        val persona = backendSettings.persona
        val base = when (backendSettings.backendType) {
            BackendType.ON_DEVICE -> persona.systemInstruction
            else -> persona.systemInstruction + WEB_TOOL_INSTRUCTION
        }
        // The user's own standing instructions and taught facts go last, so
        // they take precedence over the persona's defaults.
        val systemInstruction = base + userInstructions.promptBlock()

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
            BackendType.CLOUD_API -> {
                if (backendSettings.cloudApiKey.isBlank() || backendSettings.cloudModel.isBlank()) {
                    _modelState.value = ModelState.NotSetUp
                    return
                }
                connectBackend(
                    CloudApiChatBackend(
                        provider = backendSettings.cloudProvider,
                        apiKey = backendSettings.cloudApiKey,
                        model = backendSettings.cloudModel,
                        baseUrl = backendSettings.cloudBaseUrl.ifBlank {
                            BackendSettings.defaultBaseUrlFor(backendSettings.cloudProvider)
                        },
                        systemInstruction = systemInstruction,
                    )
                )
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
                AppLogger.i(TAG, "Backend connected ($name)")
                // Hand the fresh backend the tail of the visible conversation
                // on the next message, so it picks up where the user left off.
                pendingRecap = conversationStore.recapBlock(_messages.value)
                val greeting = if (_messages.value.any { it.sender != Sender.SYSTEM }) {
                    "$name is back. Picking up where you left off."
                } else {
                    "$name is ready. Ask me anything, or tell me to do something on your phone."
                }
                postMessage(Sender.SYSTEM, greeting)
            }.onFailure {
                AppLogger.e(TAG, "Backend connect failed", it)
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
            result.onSuccess {
                AppLogger.i(TAG, "Model import complete")
                initializeBackend()
            }.onFailure {
                AppLogger.e(TAG, "Model import failed", it)
                _modelState.value = ModelState.Error(it.message ?: "Import failed")
            }
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

        // Anything durable the user just said gets saved before the reply is
        // generated, so "remember X" then "what's X?" works in one breath.
        captureMemories(text)

        // The JSON tool-directive loop only applies to the text-protocol
        // backends; on-device uses LiteRT's native @Tool calls internally.
        val toolLoopEnabled = backendSettings.backendType != BackendType.ON_DEVICE

        viewModelScope.launch {
            _isGenerating.value = true
            // Relevant memories ride along with this turn rather than living
            // in the system prompt: the prompt is fixed when the backend
            // connects, but what's worth recalling changes every message.
            var prompt = memoryStore.recallBlock(text) + pendingRecap + text
            pendingRecap = ""
            var hops = 0
            var finalText = ""
            var stalled = false

            while (true) {
                val builder = StringBuilder()
                runCatching {
                    activeBackend.sendMessageStream(prompt)
                        .timeout(GENERATION_IDLE_TIMEOUT)
                        .collect { chunk ->
                            builder.append(chunk)
                            // Don't paint a raw tool-directive JSON into the bubble —
                            // if the reply starts like JSON, hold rendering until we
                            // know whether it's a directive or a real (odd) answer.
                            if (!builder.toString().trimStart().startsWith("{")) {
                                updateMessage(replyId, builder.toString(), isStreaming = true)
                            }
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

                val full = builder.toString()
                val directive = if (toolLoopEnabled && !stalled) parseToolDirective(full) else null
                if (directive != null && hops < MAX_TOOL_HOPS) {
                    hops++
                    updateMessage(replyId, "Accessing data…", isStreaming = true)
                    val (card, nextPrompt) = runToolDirective(directive)
                    card?.let { insertCardBefore(replyId, it) }
                    prompt = nextPrompt
                    continue
                }

                finalText = full
                break
            }

            _isGenerating.value = false
            updateMessage(replyId, finalText, isStreaming = false)
            if (_ttsEnabled.value) {
                currentlySpokenText = finalText
                tts.speak(finalText)
            }
            // A stall likely means the underlying engine/conversation (most
            // plausible on-device, e.g. wedged mid tool-call) won't recover on
            // its own — reconnect so the next message gets a fresh one rather
            // than hanging again. Off the main dispatcher: initializeBackend()
            // synchronously closes the old engine/conversation, and a wedged
            // native call there shouldn't get a chance to freeze the UI too.
            if (stalled) {
                AppLogger.w(TAG, "Generation stalled (${GENERATION_IDLE_TIMEOUT} idle) — reconnecting backend")
                withContext(Dispatchers.IO) { initializeBackend() }
            }
        }
    }

    // -- Memory --------------------------------------------------------------------

    /**
     * Saves anything durable from the user's message. The model can also
     * store memories itself (the rememberFact tool / remember directive),
     * but small on-device models call tools unreliably, so this pattern-based
     * pass runs on every backend as a backstop — see [MemoryExtractor].
     */
    private fun captureMemories(userText: String) {
        MemoryExtractor.extract(userText).forEach { (text, kind) ->
            memoryStore.remember(text, kind)
        }
    }

    fun forgetMemory(id: String) = memoryStore.forget(id)

    fun forgetAllMemories() = memoryStore.forgetAll()

    fun addMemoryManually(text: String) {
        memoryStore.remember(text, MemoryKind.FACT)
    }

    /** Wipes the saved transcript and starts a clean conversation. */
    fun clearConversation() {
        _messages.value = emptyList()
        conversationStore.clear()
        pendingRecap = ""
        AppLogger.i(TAG, "Conversation cleared — reconnecting for a clean slate")
        initializeBackend()
    }

    // -- Instructions & memory -----------------------------------------------------

    private val _customInstructions = MutableStateFlow(userInstructions.customInstructions)
    val customInstructions: StateFlow<String> = _customInstructions.asStateFlow()

    fun saveCustomInstructions(text: String) {
        if (text == userInstructions.customInstructions) return
        userInstructions.customInstructions = text
        _customInstructions.value = userInstructions.customInstructions
        AppLogger.i(TAG, "Custom instructions updated — reconnecting backend")
        // The instructions live in the system prompt, which is fixed at
        // connect time on every backend, so this needs a reconnect to apply.
        initializeBackend()
    }

    // -- Screen vision -------------------------------------------------------------

    private val _continuousScreenView = MutableStateFlow(false)
    val continuousScreenView: StateFlow<Boolean> = _continuousScreenView.asStateFlow()

    val hasScreenConsent: Boolean get() = ScreenCaptureManager.hasConsent

    fun setContinuousScreenView(enabled: Boolean) {
        _continuousScreenView.value = enabled
        AppLogger.i(TAG, "Continuous screen view: $enabled")
    }

    /**
     * Sends [question] along with a fresh screenshot so a multimodal backend
     * can answer about what the user is actually looking at.
     *
     * Falls back to the accessibility service's screen *text* when the active
     * backend can't take images (the on-device models are text-only) — which
     * for UI questions is often the better answer anyway, since it returns
     * real labels instead of pixels.
     */
    @OptIn(FlowPreview::class)
    fun sendWithScreenshot(question: String) {
        val activeBackend = backend
        if (activeBackend == null || _modelState.value !is ModelState.Ready || _isGenerating.value) return

        if (!activeBackend.supportsImages) {
            val screenText = JarvisAccessibilityService.readScreenText()
            sendMessage(
                "$question\n\n[Screen contents, read via accessibility]\n$screenText"
            )
            return
        }
        if (!ScreenCaptureManager.hasConsent) {
            postMessage(Sender.SYSTEM, "Tap the screen-view button again and allow screen capture first.")
            return
        }

        postMessage(Sender.USER, question)
        val replyId = UUID.randomUUID().toString()
        _messages.value = _messages.value + ChatMessage(replyId, Sender.JARVIS, "", isStreaming = true)

        viewModelScope.launch {
            _isGenerating.value = true
            val builder = StringBuilder()
            runCatching {
                val jpeg = ScreenCaptureManager.captureBase64Jpeg(getApplication())
                    ?: error("Couldn't capture the screen.")
                activeBackend.sendMessageWithImageStream(question, jpeg)
                    .timeout(GENERATION_IDLE_TIMEOUT)
                    .collect { chunk ->
                        builder.append(chunk)
                        updateMessage(replyId, builder.toString(), isStreaming = true)
                    }
            }.onFailure {
                AppLogger.e(TAG, "Screen-view turn failed", it)
                builder.append(if (builder.isEmpty()) "[Error: ${it.message}]" else "\n\n[Error: ${it.message}]")
            }
            _isGenerating.value = false
            updateMessage(replyId, builder.toString(), isStreaming = false)
            if (_ttsEnabled.value) {
                currentlySpokenText = builder.toString()
                tts.speak(builder.toString())
            }
        }
    }

    /**
     * Executes one tool directive and returns the data card to show (null if
     * nothing visual) plus the follow-up prompt that feeds the results back
     * to the model for its real answer.
     */
    private suspend fun runToolDirective(directive: ToolDirective): Pair<DataCard?, String> =
        withContext(Dispatchers.IO) {
            runCatching {
                when (directive.tool) {
                    "web_search" -> {
                        val query = directive.query.orEmpty()
                        val results = WebTools.search(query)
                        val card = DataCard(
                            title = "WEB SEARCH — $query",
                            entries = results.map { DataCardEntry(it.title, it.snippet, it.url) },
                        )
                        val prompt = buildString {
                            appendLine("[TOOL RESULT — web_search: \"$query\"]")
                            if (results.isEmpty()) appendLine("No results found.")
                            results.forEachIndexed { i, r ->
                                appendLine("${i + 1}. ${r.title}\n   ${r.snippet}\n   ${r.url}")
                            }
                            append(
                                "Using these results, answer the user's original question " +
                                    "conversationally. Cite the source name inline where relevant. " +
                                    "Only emit another tool directive if you truly need more data."
                            )
                        }
                        card to prompt
                    }

                    "fetch_page" -> {
                        val url = directive.url.orEmpty()
                        val content = WebTools.fetchPage(url)
                        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url
                        val card = DataCard(
                            title = "FETCHED — $host",
                            entries = listOf(DataCardEntry(host, content.take(180) + "…", url)),
                        )
                        val prompt = "[TOOL RESULT — fetch_page: $url]\n$content\n\n" +
                            "Using this page content, answer the user's original question conversationally."
                        card to prompt
                    }

                    "remember" -> {
                        val fact = directive.query.orEmpty()
                        val saved = memoryStore.remember(fact, MemoryKind.FACT)
                        null to if (saved != null) {
                            "[TOOL RESULT — remember] Saved. Now reply to the user normally, " +
                                "acknowledging it briefly and in character."
                        } else {
                            "[TOOL RESULT — remember] Already known. Reply to the user normally."
                        }
                    }

                    else -> null to "[TOOL ERROR] Unknown tool \"${directive.tool}\". " +
                        "Answer from your own knowledge, without tool directives."
                }
            }.getOrElse { e ->
                AppLogger.e(TAG, "Tool ${directive.tool} failed", e)
                null to "[TOOL ERROR] ${directive.tool} failed: ${e.message}. " +
                    "Tell the user you couldn't fetch live data, then answer from your own knowledge."
            }
        }

    /** Inserts a data-card message just above the streaming reply bubble. */
    private fun insertCardBefore(replyId: String, card: DataCard) {
        val list = _messages.value.toMutableList()
        val index = list.indexOfFirst { it.id == replyId }.takeIf { it >= 0 } ?: list.size
        list.add(index, ChatMessage(UUID.randomUUID().toString(), Sender.JARVIS, "", dataCard = card))
        _messages.value = list
    }

    fun startVoiceInput() {
        if (!speechToText.isAvailable()) {
            postMessage(Sender.SYSTEM, "Speech recognition isn't available on this device.")
            return
        }
        speechToText.startListening(
            onListeningChanged = { _isListening.value = it },
            onFinalResult = { text -> sendMessage(text) },
            onError = { message ->
                AppLogger.w(TAG, "Speech error: $message")
                if (_isListening.value) postMessage(Sender.SYSTEM, message)
            },
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
        // Persist once a reply has settled, not on every streamed token.
        if (!isStreaming) conversationStore.save(_messages.value)
    }

    override fun onCleared() {
        super.onCleared()
        voiceModeCooldownJob?.cancel()
        speechToText.stopListening()
        tts.shutdown()
        backend?.close()
        val app = getApplication<Application>()
        app.stopService(Intent(app, VoiceSessionService::class.java))
    }

    companion object {
        private const val VOICE_MODE_COOLDOWN_MS = 700L
        private const val MAX_CONSECUTIVE_VOICE_MODE_ERRORS = 3

        // How long to wait for the *next* chunk before giving up on a reply.
        // Some on-device generations can legitimately take a while (cold
        // start, long answers), so this is an idle timeout - it resets on
        // every chunk - not a hard cap on total response time.
        private val GENERATION_IDLE_TIMEOUT = 45.seconds

        /** Max tool round-trips per user message before forcing a plain answer. */
        private const val MAX_TOOL_HOPS = 3

        /**
         * Appended to the persona system prompt for the Ollama and Cloud API
         * backends (on-device gets real @Tool calls instead). One protocol
         * for every provider: the model asks for a tool by replying with a
         * bare JSON line; sendMessage()'s loop intercepts it, runs the tool,
         * and feeds the results back.
         */
        private val WEB_TOOL_INSTRUCTION = """


            You can browse the web. When you need current or factual
            information you don't reliably know (news, weather, prices,
            sports, "near me" queries, anything after your training data),
            reply with ONLY a single line of JSON and absolutely nothing
            else — no prose, no code fences:
            {"tool":"web_search","query":"<search terms>"}
            or, to read a specific page:
            {"tool":"fetch_page","url":"https://..."}
            You will receive the results in the next message; then answer the
            user's question normally. Never invent live data, and never claim
            to be "checking" something without emitting a tool line.

            You also have long-term memory. To save something about the user
            worth knowing in future conversations, use the same format:
            {"tool":"remember","query":"<one short sentence>"}
            Memories relevant to the current message are given to you
            automatically at the top of it — use them naturally, and don't
            announce that you're remembering or recalling.
        """.trimIndent()
    }
}
