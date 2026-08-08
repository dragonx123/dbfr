package com.jarvis.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.model.BackendType
import com.jarvis.assistant.model.ChatMessage
import com.jarvis.assistant.model.Sender

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    modelState: ModelState,
    backendType: BackendType,
    personaName: String,
    messages: List<ChatMessage>,
    isListening: Boolean,
    isGenerating: Boolean,
    ttsEnabled: Boolean,
    wakeWordEnabled: Boolean,
    onSend: (String) -> Unit,
    onMicClick: () -> Unit,
    onToggleTts: () -> Unit,
    onToggleWakeWord: (Boolean) -> Unit,
    onPickModel: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenVoiceMode: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(personaName) },
                actions = {
                    if (modelState is ModelState.Ready) {
                        IconButton(onClick = onOpenVoiceMode) {
                            Icon(
                                Icons.Filled.GraphicEq,
                                contentDescription = "Open voice mode",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    IconButton(onClick = { onToggleWakeWord(!wakeWordEnabled) }) {
                        Icon(
                            Icons.Filled.SettingsVoice,
                            contentDescription = "Toggle wake word listening",
                            tint = if (wakeWordEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onToggleTts) {
                        Icon(
                            if (ttsEnabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                            contentDescription = "Toggle spoken replies",
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (modelState) {
                is ModelState.NotSetUp -> ModelSetupPrompt(backendType, onPickModel, onOpenSettings)
                is ModelState.Importing -> ModelProgress("Importing model… ${modelState.bytesCopied / (1024 * 1024)} MB")
                is ModelState.Loading -> ModelProgress(
                    if (backendType == BackendType.OLLAMA) "Connecting to Ollama…" else "Loading model into memory…"
                )
                is ModelState.Error -> ModelSetupPrompt(backendType, onPickModel, onOpenSettings, error = modelState.message)
                is ModelState.Ready -> {
                    MessageList(messages, Modifier.weight(1f))
                    InputBar(
                        isListening = isListening,
                        isGenerating = isGenerating,
                        onSend = onSend,
                        onMicClick = onMicClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelSetupPrompt(
    backendType: BackendType,
    onPickModel: () -> Unit,
    onOpenSettings: () -> Unit,
    error: String? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (backendType == BackendType.OLLAMA) {
            Text("Ollama not configured", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Jarvis is set to use an Ollama server but doesn't have a server URL " +
                    "and model set yet. Open Settings to configure it.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.Button(onClick = onOpenSettings) {
                Text("Open Settings")
            }
        } else {
            Text("No model loaded", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Jarvis runs a language model entirely on this phone. Download a " +
                    ".litertlm file (e.g. Gemma3-1B-IT from huggingface.co/litert-community) " +
                    "and import it below.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.Button(onClick = onPickModel) {
                Text("Choose model file")
            }
        }
    }
}

@Composable
private fun ModelProgress(label: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun MessageList(messages: List<ChatMessage>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(messages, key = { it.id }) { message -> MessageBubble(message) }
    }
    androidx.compose.runtime.LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.sender == Sender.USER
    val isSystem = message.sender == Sender.SYSTEM
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = when {
        isUser -> MaterialTheme.colorScheme.primary
        isSystem -> Color.Transparent
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = when {
        isUser -> MaterialTheme.colorScheme.onPrimary
        isSystem -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.padding(
                start = if (isUser) 48.dp else 0.dp,
                end = if (isUser) 0.dp else 48.dp,
            ),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = message.text.ifBlank { "…" },
                    color = textColor,
                    fontWeight = if (isSystem) FontWeight.Normal else FontWeight.Normal,
                    style = if (isSystem) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun InputBar(
    isListening: Boolean,
    isGenerating: Boolean,
    onSend: (String) -> Unit,
    onMicClick: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }

    Column {
        if (isGenerating) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onMicClick) {
                Icon(
                    if (isListening) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = if (isListening) "Stop listening" else "Speak to Jarvis",
                    tint = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (isListening) "Listening…" else "Message Jarvis") },
                shape = RoundedCornerShape(24.dp),
                singleLine = true,
            )
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onSend(text)
                        text = ""
                    }
                },
                enabled = text.isNotBlank() && !isGenerating,
            ) {
                Icon(Icons.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

