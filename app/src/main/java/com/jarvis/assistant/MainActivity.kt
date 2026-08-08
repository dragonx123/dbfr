package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.jarvis.assistant.control.JarvisAccessibilityService
import com.jarvis.assistant.control.ScreenCaptureManager
import com.jarvis.assistant.ui.ChatScreen
import com.jarvis.assistant.ui.ChatViewModel
import com.jarvis.assistant.ui.CrashReportDialog
import com.jarvis.assistant.ui.InstructionsScreen
import com.jarvis.assistant.ui.LogsScreen
import com.jarvis.assistant.ui.MemoryScreen
import com.jarvis.assistant.ui.SettingsScreen
import com.jarvis.assistant.ui.VoiceModeScreen
import com.jarvis.assistant.ui.orbColor
import com.jarvis.assistant.ui.theme.JarvisTheme
import com.jarvis.assistant.util.AppLogger
import com.jarvis.assistant.util.CrashReporter

private enum class Screen { CHAT, SETTINGS, VOICE_MODE, LOGS, INSTRUCTIONS, MEMORY }

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* no-op: user can retry the mic button if denied */ }

    private val pickModelFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::importModel) }

    /** What the user typed before being asked for screen-capture consent. */
    private var pendingScreenQuestion: String? = null

    /** Set when consent was requested in order to turn on continuous watching. */
    private var pendingEnableScreenWatch = false

    private val requestScreenCapture = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        ScreenCaptureManager.onConsentResult(result.resultCode, result.data)
        val question = pendingScreenQuestion
        val enableWatch = pendingEnableScreenWatch
        pendingScreenQuestion = null
        pendingEnableScreenWatch = false
        if (!ScreenCaptureManager.hasConsent) return@registerForActivityResult
        if (question != null) viewModel.sendWithScreenshot(question)
        if (enableWatch) viewModel.setContinuousScreenView(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()

        setContent {
            JarvisTheme {
                var screen by remember { mutableStateOf(Screen.CHAT) }
                var crashLog by remember { mutableStateOf(CrashReporter.readLastCrash(this@MainActivity)) }

                val modelState by viewModel.modelState.collectAsState()
                val backendType by viewModel.backendType.collectAsState()
                val ollamaBaseUrl by viewModel.ollamaBaseUrl.collectAsState()
                val ollamaModel by viewModel.ollamaModel.collectAsState()
                val persona by viewModel.persona.collectAsState()
                val messages by viewModel.messages.collectAsState()
                val isListening by viewModel.isListening.collectAsState()
                val isGenerating by viewModel.isGenerating.collectAsState()
                val ttsEnabled by viewModel.ttsEnabled.collectAsState()
                val wakeWordEnabled by viewModel.wakeWordEnabled.collectAsState()

                val cloudProvider by viewModel.cloudProvider.collectAsState()
                val cloudApiKey by viewModel.cloudApiKey.collectAsState()
                val cloudModel by viewModel.cloudModel.collectAsState()
                val cloudBaseUrl by viewModel.cloudBaseUrl.collectAsState()
                val memories by viewModel.memories.collectAsState()

                when (screen) {
                    Screen.SETTINGS -> SettingsScreen(
                        currentBackendType = backendType,
                        currentOllamaUrl = ollamaBaseUrl,
                        currentOllamaModel = ollamaModel,
                        currentCloudProvider = cloudProvider,
                        currentCloudApiKey = cloudApiKey,
                        currentCloudModel = cloudModel,
                        currentCloudBaseUrl = cloudBaseUrl,
                        currentPersona = persona,
                        onSave = viewModel::updateSettings,
                        onPreviewVoice = viewModel::previewVoice,
                        onOpenLogs = { screen = Screen.LOGS },
                        onOpenInstructions = { screen = Screen.INSTRUCTIONS },
                        onOpenMemory = { screen = Screen.MEMORY },
                        memoryCount = memories.size,
                        onOpenAccessibilitySettings = {
                            runCatching {
                                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        },
                        screenControlEnabled = JarvisAccessibilityService.isEnabled,
                        onBack = { screen = Screen.CHAT },
                    )

                    Screen.MEMORY -> MemoryScreen(
                        memories = memories,
                        onAdd = viewModel::addMemoryManually,
                        onForget = viewModel::forgetMemory,
                        onForgetAll = viewModel::forgetAllMemories,
                        onBack = { screen = Screen.SETTINGS },
                    )

                    Screen.INSTRUCTIONS -> {
                        val instructions by viewModel.customInstructions.collectAsState()
                        InstructionsScreen(
                            initialInstructions = instructions,
                            onSave = viewModel::saveCustomInstructions,
                            onBack = { screen = Screen.SETTINGS },
                        )
                    }

                    Screen.LOGS -> {
                        val logEntries by AppLogger.entries.collectAsState()
                        LogsScreen(
                            entries = logEntries,
                            onShare = { text -> shareText(text, "Share logs") },
                            onClear = AppLogger::clear,
                            onBack = { screen = Screen.SETTINGS },
                        )
                    }

                    Screen.VOICE_MODE -> {
                        val orbPhase by viewModel.orbPhase.collectAsState()
                        val micLevel by viewModel.micLevel.collectAsState()
                        val partialTranscript by viewModel.voiceModePartialTranscript.collectAsState()
                        val replyText by viewModel.voiceModeReplyText.collectAsState()
                        val isMuted by viewModel.isVoiceModeMuted.collectAsState()
                        val voiceError by viewModel.voiceModeError.collectAsState()
                        val screenViewOn by viewModel.continuousScreenView.collectAsState()

                        VoiceModeScreen(
                            personaName = persona.displayName,
                            orbColor = persona.orbColor,
                            orbPhase = orbPhase,
                            micLevel = micLevel,
                            partialTranscript = partialTranscript,
                            latestReplyText = replyText,
                            isMuted = isMuted,
                            errorMessage = voiceError,
                            screenViewEnabled = screenViewOn,
                            onScreenViewToggle = {
                                if (screenViewOn) {
                                    viewModel.setContinuousScreenView(false)
                                } else if (ScreenCaptureManager.hasConsent) {
                                    viewModel.setContinuousScreenView(true)
                                } else {
                                    // Consent first; the toggle flips on once granted.
                                    pendingEnableScreenWatch = true
                                    requestScreenCapture.launch(
                                        ScreenCaptureManager.createConsentIntent(this@MainActivity)
                                    )
                                }
                            },
                            onMuteToggle = viewModel::toggleVoiceModeMute,
                            onClose = {
                                viewModel.exitVoiceMode()
                                screen = Screen.CHAT
                            },
                        )
                    }

                    Screen.CHAT -> ChatScreen(
                        modelState = modelState,
                        backendType = backendType,
                        personaName = persona.displayName,
                        personaColor = persona.orbColor,
                        messages = messages,
                        isListening = isListening,
                        isGenerating = isGenerating,
                        ttsEnabled = ttsEnabled,
                        wakeWordEnabled = wakeWordEnabled,
                        onSend = viewModel::sendMessage,
                        onMicClick = {
                            if (isListening) viewModel.stopVoiceInput() else viewModel.startVoiceInput()
                        },
                        onToggleTts = viewModel::toggleTts,
                        onToggleWakeWord = { enabled ->
                            if (enabled) requestRuntimePermissions()
                            viewModel.setWakeWordEnabled(enabled)
                        },
                        onPickModel = { pickModelFile.launch(arrayOf("*/*")) },
                        onOpenSettings = { screen = Screen.SETTINGS },
                        onSendWithScreen = { question ->
                            if (ScreenCaptureManager.hasConsent) {
                                viewModel.sendWithScreenshot(question)
                            } else {
                                // First use needs Android's screen-capture consent
                                // dialog; the question is replayed once granted.
                                pendingScreenQuestion = question
                                requestScreenCapture.launch(
                                    ScreenCaptureManager.createConsentIntent(this@MainActivity)
                                )
                            }
                        },
                        onOpenVoiceMode = {
                            requestRuntimePermissions()
                            viewModel.enterVoiceMode()
                            screen = Screen.VOICE_MODE
                        },
                        onNewConversation = viewModel::clearConversation,
                    )
                }

                crashLog?.let { text ->
                    CrashReportDialog(
                        crashText = text,
                        onShare = { shareText(it, "Share crash log") },
                        onDismiss = {
                            CrashReporter.clearLastCrash(this@MainActivity)
                            crashLog = null
                        },
                    )
                }
            }
        }
    }

    private fun requestRuntimePermissions() {
        val needed = buildList {
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) requestPermissions.launch(needed.toTypedArray())
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun shareText(text: String, chooserTitle: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(sendIntent, chooserTitle))
    }
}
