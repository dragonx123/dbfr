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
import androidx.core.content.ContextCompat
import com.jarvis.assistant.ui.ChatScreen
import com.jarvis.assistant.ui.ChatViewModel
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
                val modelState by viewModel.modelState.collectAsState()
                val messages by viewModel.messages.collectAsState()
                val isListening by viewModel.isListening.collectAsState()
                val isGenerating by viewModel.isGenerating.collectAsState()
                val ttsEnabled by viewModel.ttsEnabled.collectAsState()
                val wakeWordEnabled by viewModel.wakeWordEnabled.collectAsState()

                ChatScreen(
                    modelState = modelState,
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
                )
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
