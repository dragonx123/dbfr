package com.jarvis.assistant

import android.Manifest
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
import com.jarvis.assistant.ui.ChatScreen
import com.jarvis.assistant.ui.ChatViewModel
import com.jarvis.assistant.ui.SettingsScreen
import com.jarvis.assistant.ui.theme.JarvisTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* no-op: user can retry the mic button if denied */ }

    private val pickModelFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::importModel) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()

        setContent {
            JarvisTheme {
                var showSettings by remember { mutableStateOf(false) }

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

                if (showSettings) {
                    SettingsScreen(
                        currentBackendType = backendType,
                        currentOllamaUrl = ollamaBaseUrl,
                        currentOllamaModel = ollamaModel,
                        currentPersona = persona,
                        onSave = viewModel::updateSettings,
                        onPreviewVoice = viewModel::previewVoice,
                        onBack = { showSettings = false },
                    )
                } else {
                    ChatScreen(
                        modelState = modelState,
                        backendType = backendType,
                        personaName = persona.displayName,
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
                        onOpenSettings = { showSettings = true },
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
}
